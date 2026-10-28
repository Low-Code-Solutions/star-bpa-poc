#!/bin/sh
# PreToolUse hook (Bash). 09 L13, BPA-38.
# Blocks, before the command runs:
#   1. `git commit` while the branch is main;
#   2. `git push` to main: main, heads/main, refs/heads/main, HEAD or @ while on main, :main,
#      --delete main, wildcard refspecs, --all, --mirror, `-c remote.*`/`push.*`/`branch.*` on a push,
#      or no refspec while on main;
#   3. force pushes: --force, -f (also clustered, e.g. -uf), --force-with-lease, --mirror, +refspec;
#   4. any git command not on a read-only allowlist when the caller is the qa-reviewer subagent
#      (hook input "agent_type"), so that agent is read-only for git, not just by instruction.
# Rules 1-3 apply to this repository and its worktrees. A directory the hook cannot resolve
# (e.g. `cd "$(...)"`, a directory not yet created) counts as this repository: it fails closed.
# Rule 4 applies everywhere. Exit 2 with a message on stderr blocks the call and tells Claude why.
#
# Limits (CLAUDE.md): this guards Claude Code sessions only, not a person's own terminal. It reads
# the command text, so git aliases, scripts or programs that run git, and environment set earlier
# in the session are not seen. GitHub branch protection is the real control (09 L13, pending a plan
# decision). Quoted text containing spaces or shell operators is treated as data (a commit message),
# except the script of `sh -c` / `bash -c` / `eval`, which is checked as commands.

set -f
input=$(cat)
flat=$(printf '%s' "$input" | tr -d '\n')

# The "command" field (a JSON string; escaped quotes allowed inside).
cmd=$(printf '%s' "$flat" | sed -n -E 's/.*"command"[[:space:]]*:[[:space:]]*"(([^"\\]|\\.)*)".*/\1/p')
case $cmd in *git*) ;; *) exit 0 ;; esac

agent=$(printf '%s' "$flat" | sed -n -E 's/.*"agent_type"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p')
cwd=$(printf '%s' "$flat" | sed -n -E 's/.*"cwd"[[:space:]]*:[[:space:]]*"([^"]*)".*/\1/p')
[ -n "$cwd" ] && [ -d "$cwd" ] || cwd=${CLAUDE_PROJECT_DIR:-$(pwd)}

is_qa=0
case $agent in qa-reviewer | *:qa-reviewer) is_qa=1 ;; esac

# 1. Unescape the JSON string into shell text (backslash-newline joins lines, as the shell does),
# 2. drop heredoc bodies (data, e.g. a commit message), 3. join lines with ';'.
text=$(printf '%s' "$cmd" | awk '{
  s = $0
  gsub(/\\\\/, "__BS__", s)
  gsub(/__BS__\\n/, " ", s)
  gsub(/\\"/, "\"", s)
  gsub(/\\[nr]/, "\n", s)
  gsub(/\\t/, " ", s)
  gsub(/__BS__/, "\\", s)
  print s
}' | awk '
  skip { t = $0; sub(/^[ \t]+/, "", t); if (t == word) skip = 0; next }
  {
    line = $0
    if (match(line, /<<-?[ \t]*["\047]?[A-Za-z_][A-Za-z0-9_]*["\047]?/) && substr(line, RSTART + 2, 1) != "<") {
      w = substr(line, RSTART, RLENGTH); gsub(/[<"\047 \t-]/, "", w); word = w; skip = 1
      line = substr(line, 1, RSTART - 1) " " substr(line, RSTART + RLENGTH)
    }
    print line
  }' | tr '\n' ';')

# Command substitutions: their contents are checked as commands of their own; a __DYN__ marker
# stays where they stood (a value only known when the command runs).
extract() {
  t=$1; s=""; k=0
  while [ $k -lt 50 ]; do
    if printf '%s' "$t" | grep -q '\$([^()]*)'; then
      s="$s
$(printf '%s' "$t" | sed -n 's/.*\$(\([^()]*\)).*/\1/p')"
      t=$(printf '%s' "$t" | sed 's/\(.*\)\$([^()]*)/\1__DYN__/')
    elif printf '%s' "$t" | grep -q '`[^`]*`'; then
      s="$s
$(printf '%s' "$t" | sed -n 's/^[^`]*`\([^`]*\)`.*/\1/p')"
      t=$(printf '%s' "$t" | sed 's/`[^`]*`/__DYN__/')
    else
      break
    fi
    k=$((k + 1))
  done
  ex_text=$t; ex_subs=$s
}
extract "$text"; main_text=$ex_text; queue=$ex_subs; all_subs=""
for level in 1 2 3; do
  [ -n "$(printf '%s' "$queue" | tr -d '[:space:]')" ] || break
  extract "$queue"; all_subs="$all_subs
$ex_text"; queue=$ex_subs
done
all_subs="$all_subs
$queue"

# Quotes: a quoted string with spaces or shell operators is data (__STR__), unless it is the
# script of sh -c / bash -c / eval, which becomes commands. Other quotes are dropped ('main' -> main).
# Then backslashes and redirections go, and the text splits into simple commands.
prepare() {
  awk '{
    out = ""; n = length($0); i = 1
    while (i <= n) {
      c = substr($0, i, 1)
      if (c == "\"" || c == "\047") {
        j = i + 1
        while (j <= n && substr($0, j, 1) != c) j++
        s = substr($0, i + 1, j - i - 1)
        if (s ~ /[ \t;&|()<>]/) {
          if (out ~ /(^|[ \t;&|(\/])((sh|bash|zsh|dash)[ \t]+-[A-Za-z]*c|eval)[ \t]*$/) out = out " ; " s " ; "
          else out = out "__STR__"
        } else out = out s
        i = j + 1; continue
      }
      out = out c; i++
    }
    print out
  }' | sed -E -e 's/\\//g' -e 's/[0-9]*>&[0-9-]*/ /g' -e 's/&>/>/g' \
    -e 's/[0-9]*>>?[[:space:]]*[^[:space:];&|()]*/ /g' -e 's/<[[:space:]]*[^[:space:];&|()]*/ /g' \
  | tr ';&|(){}' '\n\n\n\n\n\n\n'
}
main_segs=$(printf '%s\n' "$main_text" | prepare)
sub_segs=$(printf '%s\n' "$all_subs" | prepare)

common_dir() { (cd "$1" 2>/dev/null && d=$(git rev-parse --git-common-dir 2>/dev/null) && cd "$d" && pwd -P); }
proj_common=""
[ -n "${CLAUDE_PROJECT_DIR:-}" ] && proj_common=$(common_dir "$CLAUDE_PROJECT_DIR")

# resolve <base> <path>: expands $CLAUDE_PROJECT_DIR, $PWD, $HOME and ~ at the start; anything
# else dynamic gives a path that does not exist, so the check fails closed.
resolve() {
  p=$2
  case $p in
    '$CLAUDE_PROJECT_DIR'*) p="${CLAUDE_PROJECT_DIR:-}${p#'$CLAUDE_PROJECT_DIR'}" ;;
    '${CLAUDE_PROJECT_DIR}'*) p="${CLAUDE_PROJECT_DIR:-}${p#'${CLAUDE_PROJECT_DIR}'}" ;;
    '$PWD'*) p="$1${p#'$PWD'}" ;;
    '${PWD}'*) p="$1${p#'${PWD}'}" ;;
    '$HOME'*) p="$HOME${p#'$HOME'}" ;;
    '~') p=$HOME ;;
    '~/'*) p="$HOME/${p#'~/'}" ;;
  esac
  case $p in *'$'* | *__DYN__* | *__STR__* | "") printf '/nonexistent/git-guard-unresolved'; return ;; esac
  case $p in /*) printf '%s' "$p" ;; *) printf '%s/%s' "$1" "$p" ;; esac
}

dir=$cwd
assumed=""   # branch an earlier `git switch/checkout` in the same command moves to
branch_of() {
  if [ -n "$assumed" ]; then printf '%s' "$assumed"; else git -C "$1" symbolic-ref --short -q HEAD 2>/dev/null; fi
}

block() {
  if [ $is_qa -eq 1 ]; then tail="The qa-reviewer agent reports findings; it never changes a repository."
  else tail="Work on a BPA-<n>-... branch and open a pull request; Ahsan merges (CLAUDE.md)."; fi
  cat >&2 <<EOF
Blocked by .claude/hooks/git-guard.sh (09 L13, BPA-38): $1
$tail Do not try to get round this guard. If you think the block is wrong, stop and tell the developer.
EOF
  exit 2
}

# Is `git <sub> <args>` read-only? (rule 4 allowlist)
reads() {
  sub=$1; shift
  case $sub in
    status | diff | log | show | rev-parse | rev-list | ls-files | ls-tree | ls-remote | cat-file | grep | \
    blame | annotate | shortlog | describe | merge-base | show-ref | for-each-ref | name-rev | whatchanged | \
    diff-tree | diff-files | diff-index | range-diff | version | help | var | check-ignore | check-attr | \
    check-ref-format | count-objects | verify-commit | verify-tag | cherry | show-branch) return 0 ;;
    stash) case ${1:-} in list | show) return 0 ;; esac; return 1 ;;
    worktree) [ "${1:-}" = list ] ;;
    notes) case ${1:-} in "" | show | list) return 0 ;; esac; return 1 ;;
    reflog) case ${1:-} in "" | show | -*) return 0 ;; esac; return 1 ;;
    remote) case ${1:-} in "" | -v | --verbose | show | get-url) return 0 ;; esac; return 1 ;;
    submodule) case ${1:-} in status | summary) return 0 ;; esac; return 1 ;;
    config)
      pos=0
      for a; do case $a in
        --unset* | --add | --replace-all | --rename-section | --remove-section | -e | --edit) return 1 ;;
        --get | --get-all | --get-regexp | --list | -l) return 0 ;;
        -*) ;;
        *) pos=$((pos + 1)) ;;
      esac; done
      [ $pos -le 1 ] ;;
    branch | tag)
      list=0; w=0; pos=0
      for a; do case $a in
        -l | --list | -r | --remotes | --all | --show-current | --contains* | --no-contains* | --merged* | \
        --no-merged* | --points-at* | -v | -vv | --verbose | -n*) list=1 ;;
        -a) if [ "$sub" = tag ]; then w=1; else list=1; fi ;;
        -d | -D | -m | -M | -c | -C | -f | -s | -u | --delete | --move | --copy | --force | --annotate | --sign | \
        --set-upstream-to* | --unset-upstream | --edit-description | -F | --file*) w=1 ;;
        -*) ;;
        *__DYN__* | *__STR__*) ;;
        *) pos=$((pos + 1)) ;;
      esac; done
      [ $w -eq 1 ] && return 1
      [ $list -eq 1 ] && return 0
      [ $pos -eq 0 ] ;;
    *) return 1 ;;
  esac
}

check_seg() {
  set -- $1
  [ $# -gt 0 ] || return 0
  case $1 in
    cd | pushd) dir=$(resolve "$dir" "${2:-$HOME}"); assumed=""; return 0 ;;
    echo | printf | grep | egrep | fgrep | rg | sed | awk | cat | head | tail | wc | ls | which | type | man | \
    hash | test | "[") return 0 ;;
  esac
  # git may come after wrappers (if, timeout 60, env -i, nice, sudo, xargs, VAR=value ...).
  gitdir=""
  while [ $# -gt 0 ]; do
    case $1 in
      git | */git) break ;;
      GIT_DIR=*) gitdir=${1#GIT_DIR=} ;;
      command) [ "${2:-}" = -v ] && return 0 ;;
    esac
    shift
  done
  [ $# -gt 0 ] || return 0
  shift

  gdir=$dir; cfg=""
  while [ $# -gt 0 ]; do
    case $1 in
      -C) shift; [ $# -gt 0 ] && { gdir=$(resolve "$gdir" "$1"); shift; } ;;
      -c) shift; [ $# -gt 0 ] && { cfg="$cfg $1"; shift; } ;;
      --git-dir) shift; [ $# -gt 0 ] && { gitdir=$1; shift; } ;;
      --git-dir=*) gitdir=${1#--git-dir=}; shift ;;
      --work-tree | --namespace) shift; [ $# -gt 0 ] && shift ;;
      -*) shift ;;
      *) break ;;
    esac
  done
  [ -n "$gitdir" ] && gdir=$(resolve "$gdir" "$gitdir")
  [ $# -gt 0 ] || return 0
  sub=$1; shift

  if [ $is_qa -eq 1 ] && ! reads "$sub" "$@"; then
    block "the qa-reviewer agent is read-only; 'git $sub' is not on its read-only list."
  fi

  if [ -n "$proj_common" ]; then
    gc=$(common_dir "$gdir")
    if [ -z "$gc" ]; then gdir=$CLAUDE_PROJECT_DIR      # unresolved: fail closed
    elif [ "$gc" != "$proj_common" ]; then return 0     # another repository
    fi
  fi

  case $sub in
    switch | checkout)
      new=""; target=""; npos=0; detach=0; dd=0
      while [ $# -gt 0 ]; do
        case $1 in
          -c | -C | -b | -B | --create | --force-create | --orphan) shift; new=${1:-} ;;
          -d | --detach) detach=1 ;;
          --) dd=1 ;;
          -*) ;;
          *) npos=$((npos + 1)); [ -z "$target" ] && target=$1 ;;
        esac
        [ $# -gt 0 ] && shift
      done
      if [ -n "$new" ]; then assumed=$new
      elif [ $detach -eq 1 ]; then assumed="(detached)"
      elif [ $dd -eq 0 ] && [ $npos -eq 1 ]; then
        case $target in
          *__DYN__* | *__STR__*) ;;
          *) git -C "$gdir" show-ref --verify -q "refs/heads/$target" 2>/dev/null && assumed=$target ;;
        esac
      fi
      ;;
    commit)
      [ "$(branch_of "$gdir")" = main ] && block "'git commit' on main. Switch to the item's branch first."
      ;;
    push)
      for k in $(printf '%s' "$cfg" | tr '[:upper:]' '[:lower:]'); do
        case $k in remote.* | push.* | branch.*) block "'git -c $k' on a push can redirect it to main or force it." ;; esac
      done
      force=0; all=0; tags=0; dyn=0; remote=""; refs=""
      while [ $# -gt 0 ]; do
        case $1 in
          --force | --force=* | --force-with-lease | --force-with-lease=* | --mirror) force=1 ;;
          --all | --branches) all=1 ;;
          --tags) tags=1 ;;
          -o | --push-option | --repo | --receive-pack | --exec) shift ;;
          --*) ;;
          -o*) ;;
          -*f*) force=1 ;;
          -*) ;;
          __DYN__ | __STR__) dyn=1 ;;
          *) if [ -z "$remote" ]; then remote=$1; else refs="$refs $1"; fi ;;
        esac
        [ $# -gt 0 ] && shift
      done
      [ $force -eq 1 ] && block "force push (--force, -f, --force-with-lease or --mirror) is not allowed."
      [ $all -eq 1 ] && block "'git push --all' also pushes main."
      cur=$(branch_of "$gdir")
      if [ -z "$refs" ]; then
        if [ $tags -eq 0 ] || [ $dyn -eq 1 ]; then
          [ "$cur" = main ] && block "'git push' on main pushes main."
        fi
        return 0
      fi
      for r in $refs; do
        case $r in +*) block "force push ('$r' refspec) is not allowed." ;; esac
        case $r in *'*'*) block "wildcard refspec '$r' also pushes main." ;; esac
        case $r in *:*) dest=${r##*:} ;; *) dest=$r ;; esac
        case $dest in @ | HEAD | "" | *__DYN__* | *__STR__*) dest=$cur ;; esac
        dest=${dest#refs/}; dest=${dest#heads/}
        [ "$dest" = main ] && block "push to main ('$r')."
      done
      [ $dyn -eq 1 ] && [ "$cur" = main ] && block "push of a computed refspec while on main."
      ;;
  esac
  return 0
}

while IFS= read -r seg; do check_seg "$seg"; done <<EOF
$main_segs
EOF
dir=$cwd; assumed=""
while IFS= read -r seg; do check_seg "$seg"; done <<EOF
$sub_segs
EOF
exit 0
