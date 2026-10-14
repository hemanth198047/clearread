# GitHub repo rename: `mdbeautifier` → `clearread`

**Status:** Waiting on the **repository owner**. Agents and GitHub Apps get `HTTP 403` on rename even when the repo is public — public visibility is not rename permission.

| Item | Value |
| --- | --- |
| Current name | `hemanth198047/mdbeautifier` |
| Target name | `hemanth198047/clearread` |
| Canonical URL | https://github.com/hemanth198047/clearread |
| Java package | `com.clearread` (unchanged; do not rename packages for the GitHub rename) |

## Owner steps (GitHub UI)

Only the owner (or someone with admin rights on the repo) can do this:

1. Open **https://github.com/hemanth198047/mdbeautifier/settings**
2. Under **General** → **Repository name**, change `mdbeautifier` to `clearread`
3. Confirm **Rename**
4. Optional: under **Danger Zone**, leave redirects enabled (GitHub redirects the old URL automatically)

After rename, verify:

- https://github.com/hemanth198047/clearread loads
- https://github.com/hemanth198047/mdbeautifier redirects to `clearread`
- Existing PR/release/issue URLs under the old path still resolve via redirect

## Clone and remotes (prefer the new name)

Docs and new clones should use the target URL now; it works after the owner rename (and redirects from the old name afterward).

```bash
# Fresh clone
git clone https://github.com/hemanth198047/clearread.git

# Existing local checkout — update origin after the GitHub rename
git remote set-url origin https://github.com/hemanth198047/clearread.git
git remote -v
```

SSH equivalent:

```bash
git remote set-url origin git@github.com:hemanth198047/clearread.git
```

## What agents already updated

- README clone / remote guidance points at `hemanth198047/clearread`
- Product name, Compose project, and Java packages remain **ClearRead** / `com.clearread`
- Historical phase PR permalinks may still show `.../mdbeautifier/pull/...`; those keep working via GitHub redirect after rename

## Agent rename attempt (for operators)

```bash
gh repo rename clearread --repo hemanth198047/mdbeautifier --yes
```

Expected without owner credentials: `HTTP 403: Resource not accessible by integration`.
