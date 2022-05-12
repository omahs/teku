/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.statetransition.attestation;

import java.util.Collection;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.ethereum.events.SlotEventsChannel;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.subscribers.Subscribers;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.service.serviceutils.Service;
import tech.pegasys.teku.spec.datastructures.attestation.ProcessedAttestationListener;
import tech.pegasys.teku.spec.datastructures.attestation.ValidateableAttestation;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.spec.datastructures.util.AttestationProcessingResult;
import tech.pegasys.teku.statetransition.block.BlockImportNotifications;
import tech.pegasys.teku.statetransition.forkchoice.ForkChoice;
import tech.pegasys.teku.statetransition.util.FutureItems;
import tech.pegasys.teku.statetransition.util.PendingPool;
import tech.pegasys.teku.statetransition.validation.AggregateAttestationValidator;
import tech.pegasys.teku.statetransition.validation.AttestationValidator;
import tech.pegasys.teku.statetransition.validation.InternalValidationResult;
import tech.pegasys.teku.statetransition.validation.ValidationResultCode;
import tech.pegasys.teku.statetransition.validation.signatures.SignatureVerificationService;
import tech.pegasys.teku.statetransition.validatorcache.ActiveValidatorChannel;
import tech.pegasys.teku.storage.protoarray.DeferredVotes;

public class AttestationManager extends Service
    implements SlotEventsChannel, BlockImportNotifications {

  private static final Logger LOG = LogManager.getLogger();
  private final ActiveValidatorChannel activeValidatorChannel;
  private static final SafeFuture<AttestationProcessingResult> ATTESTATION_SAVED_FOR_FUTURE_RESULT =
      SafeFuture.completedFuture(AttestationProcessingResult.SAVED_FOR_FUTURE);

  private final ForkChoice attestationProcessor;

  private final PendingPool<ValidateableAttestation> pendingAttestations;
  private final FutureItems<ValidateableAttestation> futureAttestations;
  private final DeferredAttestations deferredAttestations;
  private final AggregatingAttestationPool aggregatingAttestationPool;

  private final Subscribers<ProcessedAttestationListener> attestationsToSendSubscribers =
      Subscribers.create(true);
  private final Subscribers<ProcessedAttestationListener> allValidAttestationsSubscribers =
      Subscribers.create(true);

  private final AttestationValidator attestationValidator;
  private final AggregateAttestationValidator aggregateValidator;
  // SignatureVerificationService is only included here, so that it's lifecycle can be controlled by
  // AttestationManager
  private final SignatureVerificationService signatureVerificationService;

  AttestationManager(
      final ForkChoice attestationProcessor,
      final PendingPool<ValidateableAttestation> pendingAttestations,
      final FutureItems<ValidateableAttestation> futureAttestations,
      final DeferredAttestations deferredAttestations,
      final AggregatingAttestationPool aggregatingAttestationPool,
      final AttestationValidator attestationValidator,
      final AggregateAttestationValidator aggregateValidator,
      final SignatureVerificationService signatureVerificationService,
      final ActiveValidatorChannel activeValidatorChannel) {
    this.attestationProcessor = attestationProcessor;
    this.pendingAttestations = pendingAttestations;
    this.futureAttestations = futureAttestations;
    this.deferredAttestations = deferredAttestations;
    this.aggregatingAttestationPool = aggregatingAttestationPool;
    this.attestationValidator = attestationValidator;
    this.aggregateValidator = aggregateValidator;
    this.signatureVerificationService = signatureVerificationService;
    this.activeValidatorChannel = activeValidatorChannel;
  }

  public static AttestationManager create(
      final PendingPool<ValidateableAttestation> pendingAttestations,
      final FutureItems<ValidateableAttestation> futureAttestations,
      final ForkChoice attestationProcessor,
      final AggregatingAttestationPool aggregatingAttestationPool,
      final AttestationValidator attestationValidator,
      final AggregateAttestationValidator aggregateValidator,
      final SignatureVerificationService signatureVerificationService,
      final ActiveValidatorChannel activeValidatorChannel) {
    return new AttestationManager(
        attestationProcessor,
        pendingAttestations,
        futureAttestations,
        new DeferredAttestations(),
        aggregatingAttestationPool,
        attestationValidator,
        aggregateValidator,
        signatureVerificationService,
        activeValidatorChannel);
  }

  public void subscribeToAllValidAttestations(ProcessedAttestationListener listener) {
    allValidAttestationsSubscribers.subscribe(listener);
  }

  private void notifyAllValidAttestationsSubscribers(ValidateableAttestation attestation) {
    allValidAttestationsSubscribers.forEach(s -> s.accept(attestation));
  }

  public void subscribeToAttestationsToSend(
      ProcessedAttestationListener attestationsToSendListener) {
    attestationsToSendSubscribers.subscribe(attestationsToSendListener);
  }

  private void notifyAttestationsToSendSubscribers(ValidateableAttestation attestation) {
    attestationsToSendSubscribers.forEach(s -> s.accept(attestation));
  }

  public SafeFuture<InternalValidationResult> addAttestation(ValidateableAttestation attestation) {
    SafeFuture<InternalValidationResult> validationResult =
        attestationValidator.validate(attestation);
    processInternallyValidatedAttestation(validationResult, attestation);
    return validationResult;
  }

  public SafeFuture<InternalValidationResult> addAggregate(ValidateableAttestation attestation) {
    SafeFuture<InternalValidationResult> validationResult =
        aggregateValidator.validate(attestation);
    processInternallyValidatedAttestation(validationResult, attestation);
    return validationResult;
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void processInternallyValidatedAttestation(
      SafeFuture<InternalValidationResult> validationResult, ValidateableAttestation attestation) {
    validationResult.thenAccept(
        internalValidationResult -> {
          if (internalValidationResult.code().equals(ValidationResultCode.ACCEPT)
              || internalValidationResult.code().equals(ValidationResultCode.SAVE_FOR_FUTURE)) {
            onAttestation(attestation)
                .finish(
                    result ->
                        result.ifInvalid(
                            reason -> LOG.debug("Rejected received attestation: " + reason)),
                    err -> LOG.error("Failed to process received attestation.", err));
            notifyAllValidAttestationsSubscribers(attestation);
          }
        });
  }

  @Override
  public void onSlot(final UInt64 slot) {
    applyDeferredAttestations(slot);
    applyFutureAttestations(slot);
  }

  private void applyDeferredAttestations(final UInt64 slot) {
    final Collection<DeferredVotes> deferredUpdates = deferredAttestations.prune(slot);
    if (deferredUpdates.isEmpty()) {
      return;
    }
    attestationProcessor.applyDeferredAttestations(deferredUpdates);
  }

  private void applyFutureAttestations(final UInt64 slot) {
    futureAttestations.onSlot(slot);
    List<ValidateableAttestation> attestations = futureAttestations.prune(slot);
    if (attestations.isEmpty()) {
      return;
    }
    attestationProcessor.applyIndexedAttestations(attestations);
    attestations.stream()
        .filter(ValidateableAttestation::isProducedLocally)
        .filter(a -> !a.isGossiped())
        .forEach(
            a -> {
              notifyAttestationsToSendSubscribers(a);
              notifyAllValidAttestationsSubscribers(a);
            });
  }

  @Override
  public void onBlockImported(final SignedBeaconBlock block) {
    final Bytes32 blockRoot = block.getMessage().hashTreeRoot();
    activeValidatorChannel.onBlockImported(block);
    pendingAttestations
        .getItemsDependingOn(blockRoot, false)
        .forEach(
            attestation -> {
              pendingAttestations.remove(attestation);
              onAttestation(attestation)
                  .finish(
                      err ->
                          LOG.error(
                              "Failed to process pending attestation dependent on " + blockRoot,
                              err));
            });
  }

  public SafeFuture<AttestationProcessingResult> onAttestation(
      final ValidateableAttestation attestation) {
    if (pendingAttestations.contains(attestation)) {
      return ATTESTATION_SAVED_FOR_FUTURE_RESULT;
    }

    return attestationProcessor
        .onAttestation(attestation)
        .thenApply(
            result -> {
              activeValidatorChannel.onAttestation(attestation);

              switch (result.getStatus()) {
                case SUCCESSFUL:
                  LOG.trace("Processed attestation {} successfully", attestation::hashTreeRoot);
                  aggregatingAttestationPool.add(attestation);
                  sendToSubscribersIfProducedLocally(attestation);
                  break;
                case UNKNOWN_BLOCK:
                  LOG.trace(
                      "Deferring attestation {} as required block is not yet present",
                      attestation::hashTreeRoot);
                  pendingAttestations.add(attestation);
                  break;
                case DEFER_FORK_CHOICE_PROCESSING:
                  LOG.trace(
                      "Defer fork choice processing of attestation {}", attestation::hashTreeRoot);
                  sendToSubscribersIfProducedLocally(attestation);
                  aggregatingAttestationPool.add(attestation);
                  attestation
                      .getIndexedAttestation()
                      .ifPresent(deferredAttestations::addAttestation);
                  break;
                case SAVED_FOR_FUTURE:
                  LOG.trace(
                      "Deferring attestation {} until a future slot", attestation::hashTreeRoot);
                  aggregatingAttestationPool.add(attestation);
                  futureAttestations.add(attestation);
                  break;
                case INVALID:
                  break;
                default:
                  throw new UnsupportedOperationException(
                      "AttestationProcessingResult is unrecognizable");
              }
              return result;
            });
  }

  private void sendToSubscribersIfProducedLocally(ValidateableAttestation attestation) {
    if (!attestation.isProducedLocally()) {
      return;
    }
    if (attestation.isAggregate()) {
      aggregateValidator.addSeenAggregate(attestation);
    }

    notifyAttestationsToSendSubscribers(attestation);
    notifyAllValidAttestationsSubscribers(attestation);
    attestation.markGossiped();
  }

  @Override
  protected SafeFuture<?> doStart() {
    return signatureVerificationService.start();
  }

  @Override
  protected SafeFuture<?> doStop() {
    return signatureVerificationService.stop();
  }
}
