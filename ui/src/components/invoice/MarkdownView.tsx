import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';

interface Props {
  source: string;
}

/**
 * Renders extractor markdown using react-markdown + GFM. The component map
 * applies our design tokens (DM Sans / DM Mono, navy/teal/muted colors) so
 * the rendered output matches the rest of the app — no external typography
 * plugin needed. Tables, headings, lists, and inline code all get explicit
 * Tailwind classes; nothing falls through to browser defaults.
 */
export function MarkdownView({ source }: Props) {
  if (!source.trim()) {
    return <div className="text-muted italic text-sm">No markdown emitted by extractor.</div>;
  }
  return (
    <div className="text-sm leading-relaxed text-navy-1 space-y-3">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeRaw]}
        components={{
          h1: ({ children }) => (
            <h1 className="font-serif text-xl text-navy-1 border-b border-line pb-1 mt-2 mb-2">
              {children}
            </h1>
          ),
          h2: ({ children }) => (
            <h2 className="font-serif text-lg text-navy-1 mt-3 mb-1.5">{children}</h2>
          ),
          h3: ({ children }) => (
            <h3 className="font-sans font-semibold text-base text-navy-1 mt-2.5 mb-1">{children}</h3>
          ),
          h4: ({ children }) => (
            <h4 className="font-sans font-semibold text-sm uppercase tracking-wider text-muted mt-2 mb-1">
              {children}
            </h4>
          ),
          p: ({ children }) => <p className="leading-relaxed">{children}</p>,
          ul: ({ children }) => <ul className="list-disc pl-5 space-y-1">{children}</ul>,
          ol: ({ children }) => <ol className="list-decimal pl-5 space-y-1">{children}</ol>,
          li: ({ children }) => <li className="leading-relaxed">{children}</li>,
          strong: ({ children }) => (
            <strong className="font-semibold text-navy-1">{children}</strong>
          ),
          em: ({ children }) => <em className="italic">{children}</em>,
          a: ({ href, children }) => (
            <a
              href={href}
              target="_blank"
              rel="noreferrer noopener"
              className="text-teal-1 underline underline-offset-2 hover:text-teal-2"
            >
              {children}
            </a>
          ),
          code: ({ children, className }) => {
            const inline = !className;
            if (inline) {
              return (
                <code className="font-mono text-[12px] bg-slate2 px-1 py-0.5 rounded">
                  {children}
                </code>
              );
            }
            return (
              <code className="font-mono text-[12px] block">{children}</code>
            );
          },
          pre: ({ children }) => (
            <pre className="font-mono text-[12px] bg-slate2 px-3 py-2 rounded overflow-x-auto">
              {children}
            </pre>
          ),
          blockquote: ({ children }) => (
            <blockquote className="border-l-2 border-teal-1 pl-3 text-muted italic my-2">
              {children}
            </blockquote>
          ),
          hr: () => <hr className="border-line my-3" />,
          table: ({ children }) => (
            <div className="overflow-x-auto my-2">
              <table className="w-full text-xs border-collapse">{children}</table>
            </div>
          ),
          thead: ({ children }) => (
            <thead className="bg-slate2 text-[10px] uppercase tracking-widest text-muted">
              {children}
            </thead>
          ),
          th: ({ children }) => (
            <th className="border border-line px-2 py-1 text-left font-mono">{children}</th>
          ),
          td: ({ children }) => (
            <td className="border border-line px-2 py-1 align-top font-mono text-[12px]">
              {children}
            </td>
          ),
          tr: ({ children }) => <tr className="hover:bg-slate2/50">{children}</tr>,
        }}
      >
        {source}
      </ReactMarkdown>
    </div>
  );
}
