# Kestra ArgoCD Plugin

## What

- Provides plugin components under `io.kestra.plugin.argocd.apps`.
- Includes classes such as `Sync`, `Status`.

## Why

- What user problem does this solve? Teams need to interact with Argo CD using the Argo CD CLI. Tasks are executed inside a container and rely on the official Argo CD CLI to perform application synchronization and status inspection from orchestrated workflows instead of relying on manual console work, ad hoc scripts, or disconnected schedulers.
- Why would a team adopt this plugin in a workflow? It keeps Argo CD steps in the same Kestra flow as upstream preparation, approvals, retries, notifications, and downstream systems.
- What operational/business outcome does it enable? It reduces manual handoffs and fragmented tooling while improving reliability, traceability, and delivery speed for processes that depend on Argo CD.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `argocd`

Infrastructure dependencies (Docker Compose services):

- `argocd`

### Key Plugin Classes

- `io.kestra.plugin.argocd.apps.Status`
- `io.kestra.plugin.argocd.apps.Sync`

### Project Structure

```
plugin-argocd/
├── src/main/java/io/kestra/plugin/argocd/apps/
├── src/test/java/io/kestra/plugin/argocd/apps/
├── build.gradle
└── README.md
```

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
