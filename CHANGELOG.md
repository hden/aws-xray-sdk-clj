# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog 1.1.0], and this project adheres to
[Semantic Versioning].

## [Unreleased]

### Added

- `TraceConsumer`, a protocol for consuming immutable completed traces.
- `TraceRecorder` for independently bounded trace recording, and `shutdown!`
  for controlled recorder shutdown.
- `xray/xray-trace-consumer`, an AWS X-Ray SDK factory helper, and
  `CapturedTraceConsumer` for tests.

### Changed

- Centralize trace facts in one Datascript store per recorder. Trace recording
  is bounded and best-effort: full mailboxes, expired roots, and blocked or
  failing consumers drop trace data without blocking application work.
- Record a root's close timestamp before delivering its completed trace, so
  consumer backlog does not inflate its X-Ray duration.
- `promise/with-open` no longer schedules a separate common-ForkJoinPool task
  merely to close the trace entity.

### Removed

- `core/recorder`, `core/global-recorder`, `core/root-trace-id`, and
  `core/parent-id`.
- Entity `:eid`, `:done`, and send-result APIs. Root traces now start from a
  `TraceConsumer` or `TraceRecorder`.

[unreleased]: https://github.com/hden/aws-xray-sdk-clj/compare/v0.6.0...HEAD
[Keep a Changelog 1.1.0]: https://keepachangelog.com/en/1.1.0/
[Semantic Versioning]: https://semver.org/spec/v2.0.0.html
