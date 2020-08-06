/*
 * Copyright 2019 ConsenSys AG.
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

package tech.pegasys.teku.storage.client;

import static com.google.common.base.Preconditions.checkNotNull;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.compute_start_slot_at_epoch;
import static tech.pegasys.teku.datastructures.util.BeaconStateUtil.get_committee_count_per_slot;
import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;
import static tech.pegasys.teku.util.config.Constants.SLOTS_PER_EPOCH;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.UnsignedLong;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.core.StateTransition;
import tech.pegasys.teku.core.exceptions.EpochProcessingException;
import tech.pegasys.teku.core.exceptions.SlotProcessingException;
import tech.pegasys.teku.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.datastructures.blocks.BeaconBlockAndState;
import tech.pegasys.teku.datastructures.blocks.SignedBeaconBlock;
import tech.pegasys.teku.datastructures.blocks.SlotAndBlockRoot;
import tech.pegasys.teku.datastructures.state.BeaconState;
import tech.pegasys.teku.datastructures.state.CommitteeAssignment;
import tech.pegasys.teku.datastructures.util.CommitteeUtil;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.storage.api.StorageQueryChannel;
import tech.pegasys.teku.storage.store.UpdatableStore;

public class CombinedChainDataClient {
  private static final Logger LOG = LogManager.getLogger();

  private static final SafeFuture<Optional<BeaconState>> STATE_NOT_AVAILABLE =
      completedFuture(Optional.empty());
  private static final SafeFuture<Optional<SignedBeaconBlock>> BLOCK_NOT_AVAILABLE =
      completedFuture(Optional.empty());

  private final RecentChainData recentChainData;
  private final StorageQueryChannel historicalChainData;
  private final StateTransition stateTransition;

  public CombinedChainDataClient(
      final RecentChainData recentChainData, final StorageQueryChannel historicalChainData) {
    this.recentChainData = recentChainData;
    this.historicalChainData = historicalChainData;
    this.stateTransition = new StateTransition();
  }

  public CombinedChainDataClient(
      final RecentChainData recentChainData,
      final StorageQueryChannel historicalChainData,
      final StateTransition stateTransition) {
    this.recentChainData = recentChainData;
    this.historicalChainData = historicalChainData;
    this.stateTransition = stateTransition;
  }

  /**
   * Returns the block proposed at the requested slot. If the slot is empty, no block is returned.
   *
   * @param slot the slot to get the block for
   * @return the block at the requested slot or empty if the slot was empty
   */
  public SafeFuture<Optional<SignedBeaconBlock>> getBlockAtSlotExact(final UnsignedLong slot) {
    return getBlockInEffectAtSlot(slot)
        .thenApply(maybeBlock -> maybeBlock.filter(block -> block.getSlot().equals(slot)));
  }

  /**
   * Returns the block proposed for the requested slot on the chain identified by <code>
   * headBlockRoot</code>. If the slot was empty, no block is returned.
   *
   * @param slot the slot to get the block for
   * @param headBlockRoot the block root of the head of the chain
   * @return the block at the requested slot or empty if the slot was empty
   */
  public SafeFuture<Optional<SignedBeaconBlock>> getBlockAtSlotExact(
      final UnsignedLong slot, final Bytes32 headBlockRoot) {
    return getBlockInEffectAtSlot(slot, headBlockRoot)
        .thenApply(maybeBlock -> maybeBlock.filter(block -> block.getSlot().equals(slot)));
  }

  /**
   * Returns the block which was proposed in or most recently before the requested slot on the chain
   * specified by <code>headBlockRoot</code>. If the slot was empty, the block at the last filled
   * slot is returned.
   *
   * @param slot the slot to get the effective block for
   * @return the block at slot or the closest previous slot if empty
   */
  private SafeFuture<Optional<SignedBeaconBlock>> getBlockInEffectAtSlot(
      final UnsignedLong slot, Bytes32 headBlockRoot) {
    if (!isStoreAvailable()) {
      return BLOCK_NOT_AVAILABLE;
    }

    // Try to pull root from recent data
    final Optional<Bytes32> recentRoot = recentChainData.getBlockRootBySlot(slot, headBlockRoot);
    if (recentRoot.isPresent()) {
      return getBlockByBlockRoot(recentRoot.get());
    }

    return historicalChainData.getLatestFinalizedBlockAtSlot(slot);
  }

  public SafeFuture<Optional<SignedBeaconBlock>> getBlockInEffectAtSlot(final UnsignedLong slot) {
    if (!isChainDataFullyAvailable()) {
      return BLOCK_NOT_AVAILABLE;
    }

    // Try to pull root from recent data
    final Optional<Bytes32> recentRoot = recentChainData.getBlockRootBySlot(slot);
    if (recentRoot.isPresent()) {
      return getBlockByBlockRoot(recentRoot.get());
    }

    return historicalChainData.getLatestFinalizedBlockAtSlot(slot);
  }

  public SafeFuture<Optional<BeaconBlockAndState>> getBlockAndStateInEffectAtSlot(
      final UnsignedLong slot) {
    return getBlockInEffectAtSlot(slot)
        .thenCompose(
            maybeBlock ->
                maybeBlock
                    .map(SignedBeaconBlock::getMessage)
                    .map(this::getStateForBlock)
                    .orElseGet(() -> SafeFuture.completedFuture(Optional.empty())));
  }

  public SafeFuture<Optional<BeaconState>> getStateAtSlotExact(final UnsignedLong slot) {
    return getBlockAndStateInEffectAtSlot(slot)
        .thenApplyChecked(
            maybeBlockAndState ->
                maybeBlockAndState.flatMap(
                    blockAndState -> regenerateBeaconState(blockAndState.getState(), slot)));
  }

  private SafeFuture<Optional<BeaconBlockAndState>> getStateForBlock(final BeaconBlock block) {
    return getStateByBlockRoot(block.hash_tree_root())
        .thenApply(maybeState -> maybeState.map(state -> new BeaconBlockAndState(block, state)));
  }

  public boolean isFinalized(final UnsignedLong slot) {
    final UnsignedLong finalizedEpoch = recentChainData.getFinalizedEpoch();
    final UnsignedLong finalizedSlot = compute_start_slot_at_epoch(finalizedEpoch);
    return finalizedSlot.compareTo(slot) >= 0;
  }

  public boolean isFinalizedEpoch(final UnsignedLong epoch) {
    final UnsignedLong finalizedEpoch = recentChainData.getFinalizedEpoch();
    return finalizedEpoch.compareTo(epoch) >= 0;
  }

  /**
   * Returns the latest state at the given slot on the current chain.
   *
   * @param slot the slot to get the state for
   * @return the State at slot
   */
  public SafeFuture<Optional<BeaconState>> getLatestStateAtSlot(final UnsignedLong slot) {
    if (!isChainDataFullyAvailable()) {
      return STATE_NOT_AVAILABLE;
    }

    if (isRecentData(slot)) {
      return recentChainData
          .retrieveStateInEffectAtSlot(slot)
          .thenCompose(
              recentState -> {
                if (recentState.isPresent()) {
                  return completedFuture(recentState);
                }
                // Fall-through to historical query in case state has moved into historical range
                // during processing
                return historicalChainData.getLatestFinalizedStateAtSlot(slot);
              });
    }

    return historicalChainData.getLatestFinalizedStateAtSlot(slot);
  }

  public SafeFuture<Optional<BeaconState>> getStateByBlockRoot(final Bytes32 blockRoot) {
    final UpdatableStore store = getStore();
    if (store == null) {
      LOG.trace("No state at blockRoot {} because the store is not set", blockRoot);
      return STATE_NOT_AVAILABLE;
    }

    return store
        .retrieveBlockState(blockRoot)
        .thenCompose(
            maybeState -> {
              if (maybeState.isPresent()) {
                return completedFuture(maybeState);
              }
              return historicalChainData.getFinalizedStateByBlockRoot(blockRoot);
            });
  }

  public SafeFuture<Optional<BeaconState>> getStateByStateRoot(final Bytes32 stateRoot) {
    final UpdatableStore store = getStore();
    if (store == null) {
      LOG.trace("No state at stateRoot {} because the store is not set", stateRoot);
      return STATE_NOT_AVAILABLE;
    }
    return historicalChainData
        .getSlotAndBlockRootByStateRoot(stateRoot)
        .thenCompose(
            maybeSlotAndBlockRoot ->
                maybeSlotAndBlockRoot
                    .map(this::getStateFromSlotAndBlock)
                    .orElseGet(() -> getFinalizedStateFromStateRoot(stateRoot)));
  }

  private SafeFuture<Optional<BeaconState>> getFinalizedStateFromStateRoot(
      final Bytes32 stateRoot) {
    return historicalChainData
        .getFinalizedSlotByStateRoot(stateRoot)
        .thenCompose(
            maybeSlot -> maybeSlot.map(this::getStateAtSlotExact).orElse(STATE_NOT_AVAILABLE));
  }

  private SafeFuture<Optional<BeaconState>> getStateFromSlotAndBlock(
      final SlotAndBlockRoot slotAndBlockRoot) {
    return getStateByBlockRoot(slotAndBlockRoot.getBlockRoot())
        .thenApply(
            maybeState ->
                maybeState.flatMap(
                    preState -> regenerateBeaconState(preState, slotAndBlockRoot.getSlot())));
  }

  private Optional<BeaconState> regenerateBeaconState(
      final BeaconState preState, final UnsignedLong slot) {
    if (preState.getSlot().equals(slot)) {
      return Optional.of(preState);
    } else if (slot.compareTo(getBestSlot()) > 0) {
      LOG.debug("Attempted to wind forward to a future state: {}", slot.toString());
      return Optional.empty();
    }
    try {
      return Optional.of(stateTransition.process_slots(preState, slot));
    } catch (SlotProcessingException | EpochProcessingException | IllegalArgumentException e) {
      LOG.debug("State Transition error", e);
      return Optional.empty();
    }
  }

  public Optional<BeaconState> getHeadStateFromStore() {
    return recentChainData.getBestState();
  }

  public Optional<Bytes32> getBestBlockRoot() {
    return recentChainData.getBestBlockRoot();
  }

  public Optional<SignedBeaconBlock> getBestBlock() {
    return recentChainData.getBestBlock();
  }

  public boolean isStoreAvailable() {
    return recentChainData != null && recentChainData.getStore() != null;
  }

  public boolean isChainDataFullyAvailable() {
    return !recentChainData.isPreGenesis() && !recentChainData.isPreForkChoice();
  }

  public List<CommitteeAssignment> getCommitteesFromState(BeaconState state, UnsignedLong epoch) {
    List<CommitteeAssignment> result = new ArrayList<>();
    final UnsignedLong startingSlot = compute_start_slot_at_epoch(epoch);
    int committeeCount = get_committee_count_per_slot(state, epoch).intValue();
    for (int i = 0; i < SLOTS_PER_EPOCH; i++) {
      UnsignedLong slot = startingSlot.plus(UnsignedLong.valueOf(i));
      for (int j = 0; j < committeeCount; j++) {
        UnsignedLong idx = UnsignedLong.valueOf(j);
        List<Integer> committee = CommitteeUtil.get_beacon_committee(state, slot, idx);
        result.add(new CommitteeAssignment(committee, idx, slot));
      }
    }
    return result;
  }

  public UnsignedLong getBestSlot() {
    return this.recentChainData.getBestSlot();
  }

  @VisibleForTesting
  public UpdatableStore getStore() {
    return recentChainData.getStore();
  }

  public NavigableMap<UnsignedLong, Bytes32> getAncestorRoots(
      final UnsignedLong startSlot, final UnsignedLong step, final UnsignedLong count) {
    return recentChainData.getAncestorRoots(startSlot, step, count);
  }

  public SafeFuture<Optional<SignedBeaconBlock>> getBlockByBlockRoot(final Bytes32 blockRoot) {
    return recentChainData
        .retrieveSignedBlockByRoot(blockRoot)
        .thenCompose(
            maybeBlock -> {
              if (maybeBlock.isPresent()) {
                return SafeFuture.completedFuture(maybeBlock);
              }
              return historicalChainData.getBlockByBlockRoot(blockRoot);
            });
  }

  private boolean isRecentData(final UnsignedLong slot) {
    checkNotNull(slot);
    if (recentChainData.isPreGenesis()) {
      return false;
    }
    final UnsignedLong finalizedSlot = recentChainData.getStore().getLatestFinalizedBlockSlot();
    return slot.compareTo(finalizedSlot) >= 0;
  }
}
