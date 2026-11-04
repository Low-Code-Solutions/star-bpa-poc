/**
 * Messages in an alert (role="alert"). A list renders <div role="alert"><ul><li>…</li></ul></div>, as the task screens had;
 * one text renders <p role="alert">…</p>, as the login had. Nothing renders when there is nothing to say.
 */
export function ErrorMessage({ messages, text }: { messages?: string[]; text?: string | null }) {
  if (messages && messages.length > 0) {
    return (
      <div role="alert">
        <ul>
          {messages.map((m) => (
            <li key={m}>{m}</li>
          ))}
        </ul>
      </div>
    );
  }
  if (text) {
    return <p role="alert">{text}</p>;
  }
  return null;
}
