# Changelog

## Upcoming Breaking Changes
- The `/teku/v1/beacon/states/:state_id` endpoint has been deprecated in favor of the standard API `/eth/v1/debug/beacon/states/:state_id` which now returns the state as SSZ when the `Accept: application/octet-stream` header is specified on the request.
- The `/eth/v1/debug/beacon/states/:state_id` endpoint has been deprecated in favor of the v2 Altair endpoint `/eth/v2/debug/beacon/states/:state_id`
- The `/eth/v1/beacon/blocks/:block_id` endpoint has been deprecated in favor of the v2 Altair endpoint `/eth/v2/beacon/blocks/:block_id`
- The `/eth/v1/validator/blocks/:slot` endpoint has been deprecated in favor of the v2 Altair endpoint `/eth/v2/validator/blocks/:slot`
- The `-jdk14` and `-jdk15` docker image variants will be removed in a future release. JDK 14 and 15 are no longer receiving security updates from upstream vendors.
  Note that the default docker image usage JDK 16 and is still receiving security updates.

## Current Releases
For information on changes in released versions of Teku, see the [releases page](https://github.com/ConsenSys/teku/releases).

## Unreleased Changes

### Breaking Changes

### Additions and Improvements
 - Logged a message to indicate when the node is in sync.
 - Upgraded jdk16 and default docker image to use eclipse-tumerin builds of OpenJDK.
 - jdk14 and jdk15 docker images have been upgraded to use the latest Ubuntu. Note that these images will be removed in future versions.
 - Reduced memory usage and GC pressure created while tracking the latest attestations for each validator.

### Bug Fixes
 - Fixed `IllegalStateException: New response submitted after closing AsyncResponseProcessor` errors.
