#!/bin/sh
# PreToolUse hook (Bash). 09 L13, BPA-38.
# Blocks, before the command runs:
#   1. `git commit` while the branch is main;
#   2. `git push` to main: main, HEAD:main, refs/heads/main, :main, --delete main, --all, --mirror,
#      or no refspec while on main;
#   3. force pushes: --force, -f (also clustered, e.g. -uf), --force-with-lease, +refspec;
#   4. any git command that changes a repository when the caller is the qa-reviewer subagent
#      (hook input "agent_type"), so that agent is read-only for git, not just by instruction.
# Rules 1-3 apply to this repository and its worktrees only; rule 4 applies everywhere.
# Exit 2 with a message on stderr blocks the call and tells Claude why.
#
# Limits (CLAUDE.md): this guards Claude Code sessions only, not a person's own terminal. It reads
# the command text, so git aliases, scripts that run git, and `eval` are not seen. GitHub branch
# protection is the real control (09 L13, pending a plan decision). Quoted text that looks like a
# separate git command (e.g. `echo "x; git push origin main"`) may be blocked by mistake; rephrase.

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

# Unescape JSON; newlines become command separators; drop quotes (so 'push' reads as push).
text=$(printf '%s' "$cmd" | sed \
  -e 's/\\\\/__BS__/g' -e 's/\\"/"/g' -e 's/\\[nr]/ ; /g' -e 's/\\t/ /g' -e 's/__BS__/\\/g' \
  | tr -d "\"'")

# Command substitutions: check their contents as commands of their own, and leave a __DYN__
# marker where they stood (a value only known when the command runs).
subs=""
n=0
while printf '%s' "$text" | grep -q '\$([^()]*)' && [ $n -lt 50 ]; do
  subs="$subs
$(printf '%s' "$text" | sed -n 's/.*\$(\([^()]*\)).*/\1/p')"
  text=$(printf '%s' "$text" | sed 's/\(.*\)\$([^()]*)/\1 __DYN__ /')
  n=$((n + 1))
done
while printf '%s' "$text" | grep -q '`[^`]*`' && [ $n -lt 100 ]; do
  subs="$subs
$(printf '%s' "$text" | sed -n 's/^[^`]*`\([^`]*\)`.*/\1/p')"
  text=$(printf '%s' "$text" | sed 's/`[^`]*`/ __DYN__ /')
  n=$((n + 1))
done

# Split into simple commands on ; & | ( ) { } and newlines, after removing 2>&1-style redirections.
split() { sed -e 's/[0-9]*>&[0-9-]*/ /g' -e 's/&>/>/g' | tr ';&|(){}' '\n\n\n\n\n\n\n'; }
main_segs=$(printf '%s\n' "$text" | split)
sub_segs=$(printf '%s\n' "$subs" | split)

common_dir() { (cd "$1" 2>/dev/null && d=$(git rev-parse --git-common-dir 2>/dev/null) && cd "$d" && pwd -P); }
proj_common=""
[ -n "${CLAUDE_PROJECT_DIR:-}" ] && proj_common=$(common_dir "$CLAUDE_PROJECT_DIR")
in_project() {
  [ -n "$proj_common" ] || return 0
  [ "$(common_dir "$1")" = "$proj_common" ]
}
resolve() { case $2 in /*) printf '%s' "$2" ;; "~") printf '%s' "$HOME" ;; "~/"*) printf '%s/%s' "$HOME" "${2#\~/}" ;; *) printf '%s/%s' "$1" "$2" ;; esac; }

dir=$cwd
assumed=""   # branch an earlier `git switch/checkout` in the same command moves to
branch_of() {
  if [ -n "$assumed" ]; then printf '%s' "$assumed"; else git -C "$1" symbolic-ref --short -q HEAD 2>/dev/null; fi
}

block() {
  cat >&2 <<EOF
Blocked by .claude/hooks/git-guard.sh (09 L13, BPA-38): $1
Work on a BPA-<n>-... branch and open a pull request; Ahsan merges (CLAUDE.md). Do not try to get
round this guard. If you think the block is wrong, stop and tell the developer.
EOF
  exit 2
}

# Is `git <sub> <args>` a command that changes a repository? (rule 4)
writes() {
  sub=$1; shift
  case $sub in
    add | am | apply | checkout | cherry-pick | clean | clone | commit | filter-branch | gc | init | merge | \
    mv | notes | prune | pull | push | rebase | replace | reset | restore | revert | rm | switch | \
    update-index | update-ref | worktree) return 0 ;;
    stash) case ${1:-} in list | show) return 1 ;; *) return 0 ;; esac ;;
    remote) case ${1:-} in add | remove | rm | rename | set-url | set-head | set-branches | prune | update) return 0 ;; *) return 1 ;; esac ;;
    config)
      pos=0
      for a; do case $a in
        --get | --get-all | --get-regexp | --list | -l) return 1 ;;
        --unset* | --add | --replace-all | --rename-section | --remove-section | -e | --edit) return 0 ;;
        -*) ;;
        *) pos=$((pos + 1)) ;;
      esac; done
      [ $pos -ge 2 ] ;;
    branch | tag)
      list=0; w=0; pos=0
      for a; do case $a in
        -l | --list | -r | --remotes | --all | --show-current | --contains* | --no-contains* | --merged* | \
        --no-merged* | --points-at* | -v | -vv | --verbose | -n*) list=1 ;;
        -a) if [ "$sub" = tag ]; then w=1; else list=1; fi ;;
        -d | -D | -m | -M | -c | -C | -f | -s | -u | --delete | --move | --copy | --force | --annotate | --sign | \
        --set-upstream-to* | --unset-upstream | --edit-description | -F | --file*) w=1 ;;
        -*) ;;
        __DYN__) ;;
        *) pos=$((pos + 1)) ;;
      esac; done
      [ $w -eq 1 ] && return 0
      [ $list -eq 1 ] && return 1
      [ $pos -ge 1 ] ;;
    *) return 1 ;;
  esac
}

check_seg() {
  set -- $1
  [ $# -gt 0 ] || return 0
  case $1 in
    cd | pushd) dir=$(resolve "$dir" "${2:-$HOME}"); assumed=""; return 0 ;;
  esac
  # Words that may come before git: env assignments and wrappers.
  while [ $# -gt 0 ]; do
    case $1 in
      sudo | env | command | exec | time | nohup | then | do | else | xargs | "!" | __DYN__ | *=*) shift ;;
      *) break ;;
    esac
  done
  [ $# -gt 0 ] || return 0
  case $1 in git | */git) shift ;; *) return 0 ;; esac

  gdir=$dir
  while [ $# -gt 0 ]; do
    case $1 in
      -C) shift; [ $# -gt 0 ] && { gdir=$(resolve "$gdir" "$1"); shift; } ;;
      -c | --git-dir | --work-tree | --namespace | --exec-path | --config-env) shift; [ $# -gt 0 ] && shift ;;
      -*) shift ;;
      *) break ;;
    esac
  done
  [ $# -gt 0 ] || return 0
  sub=$1; shift

  if [ $is_qa -eq 1 ] && writes "$sub" "$@"; then
    block "the qa-reviewer agent is read-only; 'git $sub' changes a repository. Report it as a finding instead."
  fi

  in_project "$gdir" || return 0

  case $sub in
    switch | checkout)
      new=""; target=""
      while [ $# -gt 0 ]; do
        case $1 in
          -c | -C | -b | -B | --create | --force-create | --orphan) shift; new=${1:-} ;;
          --) break ;;
          -*) ;;
          __DYN__) target="" ;;
          *) [ -z "$target" ] && target=$1 ;;
        esac
        [ $# -gt 0 ] && shift
      done
      if [ -n "$new" ]; then assumed=$new
      elif [ -n "$target" ] && git -C "$gdir" show-ref --verify -q "refs/heads/$target" 2>/dev/null; then assumed=$target
      fi
      ;;
    commit)
      [ "$(branch_of "$gdir")" = main ] && block "'git commit' on main. Switch to the item's branch first."
      ;;
    push)
      force=0; all=0; tags=0; dyn=0; remote=""; refs=""
      while [ $# -gt 0 ]; do
        case $1 in
          --force | --force=* | --force-with-lease | --force-with-lease=* | --mirror) force=1 ;;
          --all | --branches) all=1 ;;
          --tags) tags=1 ;;
          -o | --push-option | --repo | --receive-pack | --exec) shift ;;
          --*) ;;
          -*f*) force=1 ;;
          -*) ;;
          __DYN__) dyn=1 ;;
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
        case $r in +*) block "force push ('+$r' refspec) is not allowed." ;; esac
        case $r in
          *:*) dest=${r##*:} ;;
          HEAD) dest=$cur ;;
          *) dest=$r ;;
        esac
        [ -n "$dest" ] || dest=$cur
        dest=${dest#refs/heads/}
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
