# Provider account sign-in

Codex, Claude Code, GitHub Copilot and Antigravity show account status instead of
an endpoint URL. Their editor uses the provider's built-in endpoint.

Multiple Accounts enables an account manager with labels, sign-in, reauthentication,
selection and deletion. The selected account appears first. Selection and label
edits are provider drafts until Save, just like managed API keys. Successful sign-in
and credential deletion persist immediately. Legacy sign-ins use the provider ID
as their token slot; additional accounts use separate IDs. Provider metadata stores
only labels, identity and opaque credential references. Tokens remain encrypted in
the adapter token stores. Deleting a provider clears all its account slots.

Sign-in uses Chrome Custom Tabs when Chrome is installed, with another Custom Tabs
browser as fallback. Unlike Android WebView, this reuses the browser's existing
cookies. The tab belongs to an app activity and is closed when authorization
finishes or is cancelled. Closing the tab cancels the pending request. Chrome's
Custom Tabs keep-alive binding complements the existing foreground service and
bounded CPU wake lock. This applies to loopback callbacks and GitHub device polling.
The exported browser keep-alive service exposes only an empty binder, with no
credential or application operations.

OEM background-management behavior still requires verification on physical devices.
