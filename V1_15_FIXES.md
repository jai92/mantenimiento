V1.15 critical fixes:
- Compress vehicle photos before storing them locally to avoid WebView/localStorage quota exhaustion.
- Make save() quota-safe and transactional.
- Prevent ghost vehicles when a save fails.
- Ensure Edit Vehicle initializes its photo state, with change/remove support.
- Keep unique vehicle IDs independent of vehicle type.
