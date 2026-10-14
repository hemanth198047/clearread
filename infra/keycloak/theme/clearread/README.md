# ClearRead Keycloak login theme

Minimal **keycloak.v2** child theme aligned to Rebeautifier (slate/amber, Fraunces/Literata/Newsreader).

## Apply

Compose mounts `infra/keycloak/theme` → `/opt/keycloak/themes`. Realm JSON sets `loginTheme: clearread`.

**Fresh volume:** `start-deps` / first Keycloak boot imports the realm with this theme.

**Existing Keycloak volume:** import is skipped. Either:

1. Wipe `clearread_keycloak` and re-import, or
2. Admin Console → Realm settings → Themes → Login theme → **clearread**

Username/password HTML lives on Keycloak’s origin (`:8081`); SPA pre-auth pages are styled separately in the ClearRead frontend.
