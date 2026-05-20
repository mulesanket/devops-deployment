# Jenkins Shared Library — ShopEase

Reusable Jenkins pipeline code shared by all ShopEase microservices.

## Why this exists

Each microservice's `Jenkinsfile` used to be ~480 lines of duplicated pipeline code. This library extracts the common logic so each service's `Jenkinsfile` becomes ~5 lines.

## Folder layout

```
jenkins-library/
├── vars/        ← global pipeline steps (each .groovy file = one callable function)
├── src/         ← reusable Groovy classes (advanced, optional)
└── resources/   ← non-Groovy files (shell scripts, YAML templates)
```

## How services use it

Each service's `Jenkinsfile` will look like:

```groovy
@Library('shopease-jenkins-library') _

shopeaseJavaPipeline(
    serviceName: 'auth-service'
)
```

## Files in `vars/`

| File | Purpose |
|---|---|
| `logger.groovy` | Color-coded log helpers (`logger.info`, `logger.success`, etc.) |
| `shopeaseJavaPipeline.groovy` | The full pipeline orchestrator (coming next) |
| Per-stage files | One file per stage (coming next) |

## Versioning

Currently consumed as `@main`. Once stable, services will pin to released tags (e.g. `@v1.0.0`) for reproducible builds.
