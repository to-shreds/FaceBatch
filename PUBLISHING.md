# Publish the private repository and GitHub Pages site

The repository is prepared for `jonathanjablon-stack/FaceBatch`. The helper below requires Git, GitHub CLI, and an authenticated GitHub account with permission to create repositories.

## Automated route

From the repository root:

```bash
bash scripts/publish-github.sh jonathanjablon-stack/FaceBatch
```

The script:

1. initializes Git when needed;
2. creates a private repository when it does not already exist;
3. pushes `main` and the snapshot tag;
4. asks GitHub to use a workflow-based Pages build;
5. starts the Pages workflow;
6. prints the expected site address.

Expected repository:

`https://github.com/jonathanjablon-stack/FaceBatch`

Expected Pages address:

`https://jonathanjablon-stack.github.io/FaceBatch/`

## GitHub account requirement

GitHub Pages from a private repository requires a GitHub plan that supports private-repository Pages. The generated website is public even when the repository remains private. Keep gateway code and Android recovery material out of the Pages artifact. This snapshot deploys only `docs/`.

## Manual route

```bash
gh auth login
gh repo create jonathanjablon-stack/FaceBatch --private --source=. --remote=origin --push
git push origin --tags
gh api --method POST repos/jonathanjablon-stack/FaceBatch/pages -f build_type=workflow
gh workflow run pages.yml --repo jonathanjablon-stack/FaceBatch
```

When GitHub reports that Pages already exists, update its build type instead:

```bash
gh api --method PUT repos/jonathanjablon-stack/FaceBatch/pages -f build_type=workflow
```

Then inspect the Actions page for the `Deploy FaceBatch Web to Pages` workflow.

## Before making the repository public

Do not change repository visibility without a fresh secret and provenance review. The `docs/` page is suitable for public hosting, but the complete repository is intentionally private.

## Gateway deployment

GitHub Pages does not run `server.mjs`. Deploy `web/gateway/` to an HTTPS Node host, configure an access token and allowed Pages origin, then put the gateway URL and token into FaceBatch Web settings.

Do not place the gateway token in the HTML, repository, workflow, or Pages configuration. Each browser stores the token only on that device when the user selects the remember option.
