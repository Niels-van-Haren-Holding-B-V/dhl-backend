# Render every Mermaid diagram in docs/architecture.md to docs/img/*.svg.
# mmdc exits non-zero if any diagram fails to compile.
.PHONY: diagrams-export
diagrams-export:
	mkdir -p docs/img
	npx -y -p @mermaid-js/mermaid-cli mmdc -i docs/architecture.md -o docs/img/architecture.md
	@ls docs/img/*.svg
