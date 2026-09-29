# Diff mode on pull requests

Diff mode (see the [usage guide](usage.md#diff-mode)) scopes a `krisprRun` to the lines a PR actually
changed: instrumentation, mutants, and the report all shrink to the diff, and the run produces two extra
files under `build/krispr/` for a workflow to post back onto the PR:

- `diff.md` — a GitHub-flavoured markdown summary (one-line totals, then survivors grouped by file with
  the source line, before/after, and a plain-English description), capped at 20 survivors with a
  "+N more in the full report" note. Meant to be posted as a single sticky PR comment.
- `diff-annotations.json` — the same survivors as a JSON array of GitHub check-run style line
  annotations (`path`, `start_line`, `end_line`, `annotation_level: "warning"`, `message`), meant for a
  workflow to turn into inline review comments (for example via a GitHub App or check-run API call).

Krispr itself makes no GitHub API calls and needs no token; everything past writing these two files is
the workflow's job.

## Sample workflow

```yaml
name: krispr diff

on:
  pull_request:

permissions:
  contents: read
  pull-requests: write

jobs:
  krispr:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          # diff mode needs the merge base of the PR's base branch, not just the tip commit.
          fetch-depth: 0

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "21"

      - name: Run krispr on the diff
        run: |
          ./gradlew krisprRun \
            -Pkrispr.diffBase=origin/${{ github.base_ref }} \
            --max-workers=4

      - name: Post survivors as a PR comment
        if: always()
        uses: marocchino/sticky-pull-request-comment@v2
        with:
          path: build/krispr/diff.md

      # Optional: turn diff-annotations.json into inline check-run annotations. Requires a step or
      # action that reads the JSON array and calls the checks API (the shape matches
      # https://docs.github.com/en/rest/checks/runs#update-a-check-run, `output.annotations`);
      # krispr does not do this itself.
```

Set `-Pkrispr.diffFailOnSurvivors=true` on the `krisprRun` line instead if the workflow should fail
the check when a mutant survives on a changed line, rather than only reporting it.

If the module has more than one `krisprRun` (multi-module build), each writes its own
`build/<module>/krispr/diff.md`; either post one comment per module or concatenate them before the
`sticky-pull-request-comment` step.
