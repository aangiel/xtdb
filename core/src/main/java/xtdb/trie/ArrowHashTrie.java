package xtdb.trie;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.complex.DenseUnionVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.StructVector;

import java.util.stream.IntStream;

public class ArrowHashTrie implements HashTrie<ArrowHashTrie.Node> {

    private static final byte BRANCH_TYPE_ID = 1;
    private static final byte LEAF_TYPE_ID = 2;

    private final DenseUnionVector nodesVec;
    private final ListVector branchVec;
    private final IntVector branchElVec;
    private final IntVector dataPageIdxVec;

    private ArrowHashTrie(DenseUnionVector nodesVec) {
        this.nodesVec = nodesVec;
        branchVec = (ListVector) nodesVec.getVectorByType(BRANCH_TYPE_ID);
        branchElVec = (IntVector) branchVec.getDataVector();
        StructVector pageVec = (StructVector) this.nodesVec.getVectorByType(LEAF_TYPE_ID);
        dataPageIdxVec = pageVec.getChild("data-page-idx", IntVector.class);
    }

    public sealed interface Node extends HashTrie.Node<Node> {
    }

    private static byte[] conjPath(byte[] path, byte idx) {
        int currentPathLength = path.length;
        var childPath = new byte[currentPathLength + 1];
        System.arraycopy(path, 0, childPath, 0, currentPathLength);
        childPath[currentPathLength] = idx;
        return childPath;
    }

    public final class Branch implements Node {

        private final byte[] path;
        private final int branchVecIdx;

        public Branch(byte[] path, int branchVecIdx) {
            this.path = path;
            this.branchVecIdx = branchVecIdx;
        }

        public Node[] children() {
            int startIdx = branchVec.getElementStartIndex(branchVecIdx);

            return IntStream.range(0, branchVec.getElementEndIndex(branchVecIdx) - startIdx)
                    .mapToObj(childBucket -> {
                        int childIdx = childBucket + startIdx;
                        return branchElVec.isNull(childIdx) ? null : forIndex(conjPath(path, (byte) childBucket), branchElVec.get(childIdx));
                    })
                    .toArray(Node[]::new);
        }

        @Override
        public byte[] path() {
            return path;
        }
    }

    public final class Leaf implements Node {

        private final byte[] path;
        private final int leafOffset;

        public Leaf(byte[] path, int leafOffset) {
            this.path = path;
            this.leafOffset = leafOffset;
        }

        public int getDataPageIndex() {
            return dataPageIdxVec.get(leafOffset);
        }

        @Override
        public byte[] path() {
            return path;
        }

        @Override
        public Node[] children() {
            return null;
        }
    }

    private Node forIndex(byte[] path, int idx) {
        var nodeOffset = nodesVec.getOffset(idx);

        return switch (nodesVec.getTypeId(idx)) {
            case 0 -> null;
            case BRANCH_TYPE_ID -> new Branch(path, nodeOffset);
            case LEAF_TYPE_ID -> new Leaf(path, nodeOffset);
            default -> throw new UnsupportedOperationException();
        };
    }

    public static HashTrie<Node> from(DenseUnionVector nodes) {
        return new ArrowHashTrie(nodes);
    }

    @Override
    public HashTrie.Node<Node> rootNode() {
        return forIndex(new byte[0], nodesVec.getValueCount() - 1);
    }
}
