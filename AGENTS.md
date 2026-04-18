# Kestra ArgoCD Plugin

## What

- Provides plugin components under `io.kestra.plugin.argocd.apps`.
- Includes classes such as `Sync`, `Status`.

## Why

- This plugin integrates Kestra with Argo CD Apps.
- It provides gitOps-focused tasks that sync or inspect an Argo CD application via the Argo CD CLI. Use `Sync` to apply the desired GitOps Git state and `Status` to read sync/health, conditions, and resources.

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
