/* Local preview runtime. No native bridge or access to app files. */
(function () {
  "use strict";
  function showError(error) {
    const render = () => {
      let panel = document.getElementById("preview-error");
      if (!panel) {
        panel = document.createElement("pre");
        panel.id = "preview-error";
        panel.style.cssText = "position:relative;z-index:2147483647;padding:16px;margin:12px;border:1px solid #d55;background:#fff0f0;color:#8b0000;white-space:pre-wrap;overflow-wrap:anywhere;font:13px monospace";
        document.body.appendChild(panel);
      }
      panel.textContent = "Preview error: " + (error && error.message ? error.message : String(error));
    };
    if (document.body) render();
    else document.addEventListener("DOMContentLoaded", render, { once: true });
  }
  window.addEventListener("error", event => {
    if (event.error || event.message) showError(event.error || event.message);
    else if (event.target && event.target.src) showError("Could not load " + event.target.src + ". Check your internet connection and reload the preview.");
  }, true);
  window.addEventListener("unhandledrejection", event => showError(event.reason));

  window.runCodePreview = function (encoded, language) {
    try {
      if (!window.Babel || !window.React || !window.ReactDOM) throw new Error("Could not load the React/JSX libraries. Check your internet connection and reload the preview.");
      const source = new TextDecoder().decode(Uint8Array.from(atob(encoded), c => c.charCodeAt(0)));
      const output = document.getElementById("preview-console");
      ["log", "info", "warn", "error"].forEach(level => {
        const original = console[level].bind(console);
        console[level] = (...args) => {
          output.textContent += args.map(arg => {
            if (typeof arg === "string") return arg;
            try { return JSON.stringify(arg, null, 2); } catch (_) { return String(arg); }
          }).join(" ") + "\n";
          original(...args);
        };
      });
      const presets = [["react", { runtime: "classic" }]];
      if (language === "typescript" || language === "tsx") presets.push(["typescript", { allExtensions: true, isTSX: language === "tsx" }]);
      const captureJsx = ({ types }) => ({ visitor: {
        ExpressionStatement(path) {
          const node = path.node.expression;
          if (path.parentPath.isProgram() && (node.type === "JSXElement" || node.type === "JSXFragment")) {
            path.node.expression = types.assignmentExpression("=", types.memberExpression(types.identifier("window"), types.identifier("__previewElement")), node);
          }
        }
      } });
      const options = { filename: language === "tsx" ? "preview.tsx" : "preview.jsx", presets, plugins: [captureJsx, "transform-modules-commonjs"], ast: true };
      const result = Babel.transform(source, options);
      // Prefer an App component, then a default export, then another declared component.
      const candidates = [];
      for (const node of result.ast.program.body) {
        const declaration = node.declaration || node;
        if ((declaration.type === "FunctionDeclaration" || declaration.type === "ClassDeclaration") && declaration.id) candidates.push(declaration.id.name);
        if (declaration.type === "VariableDeclaration") for (const item of declaration.declarations) if (item.id.type === "Identifier") candidates.push(item.id.name);
      }
      const candidate = candidates.includes("App") ? "App" : candidates.find(name => /^[A-Z]/.test(name));
      Object.keys(React).forEach(key => { if (!(key in window)) window[key] = React[key]; });
      const module = { exports: {} };
      let explicitlyRendered = false;
      const dom = Object.assign({}, ReactDOM, {
        createRoot: (...args) => { explicitlyRendered = true; return ReactDOM.createRoot(...args); },
        render: (...args) => { explicitlyRendered = true; return ReactDOM.render(...args); }
      });
      const require = name => {
        if (name === "react") return React;
        if (name === "react-dom" || name === "react-dom/client") return dom;
        throw new Error('Unsupported import "' + name + '". This preview supports React and ReactDOM; other packages need a browser script URL.');
      };
      const tail = candidate ? "\n;return typeof " + candidate + " !== 'undefined' ? " + candidate + " : undefined;" : "";
      const execute = new Function("React", "ReactDOM", "require", "module", "exports", result.code + tail);
      const inferred = execute(React, dom, require, module, module.exports);
      const component = module.exports.default || module.exports.App || inferred || window.__previewElement;
      if (!explicitlyRendered && component) {
        class PreviewErrorBoundary extends React.Component {
          constructor(props) { super(props); this.state = { failed: false }; }
          static getDerivedStateFromError() { return { failed: true }; }
          componentDidCatch(error) { showError(error); }
          render() { return this.state.failed ? null : this.props.children; }
        }
        ReactDOM.createRoot(document.getElementById("root")).render(
          React.createElement(PreviewErrorBoundary, null,
            React.isValidElement(component) ? component : React.createElement(component))
        );
      } else if (!explicitlyRendered && !output.textContent && !document.getElementById("root").hasChildNodes()) {
        output.textContent = "JavaScript finished. No component, DOM content, or console output to display.";
      }
    } catch (error) { showError(error); }
  };
})();
