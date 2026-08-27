# V1.16
Critical photo persistence fix: vehicle photos are stored in Android app-private files keyed by each vehicle ID instead of localStorage. Multiple vehicles of the same type can each have their own photo, survive app restart, and have their photo changed or deleted from Edit Vehicle. Metadata localStorage no longer contains image payloads.
