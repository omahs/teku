/*
 * Copyright ConsenSys Software Inc., 2022
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

package tech.pegasys.teku.spec.datastructures.execution.versions.eip4844;

import org.apache.tuweni.bytes.Bytes;
import tech.pegasys.teku.infrastructure.ssz.collections.impl.SszByteVectorImpl;
import tech.pegasys.teku.infrastructure.ssz.tree.TreeNode;

public class Blob extends SszByteVectorImpl {

  Blob(final BlobSchema schema, final TreeNode backingNode) {
    super(schema, backingNode);
  }

  public Blob(final BlobSchema blobSchema, final Bytes bytes) {
    super(blobSchema, bytes);
  }

  @Override
  public BlobSchema getSchema() {
    return (BlobSchema) super.getSchema();
  }
}
