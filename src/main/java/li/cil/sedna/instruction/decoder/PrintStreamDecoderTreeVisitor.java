package li.cil.sedna.instruction.decoder;

import li.cil.sedna.instruction.InstructionDeclaration;
import li.cil.sedna.utils.BitUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.PrintStream;

public final class PrintStreamDecoderTreeVisitor implements DecoderTreeVisitor {
    private final PrintStream stream;
    private final int maxDepth;

    public PrintStreamDecoderTreeVisitor() {
        this(System.out);
    }

    public PrintStreamDecoderTreeVisitor(final int alignment) {
        this(System.out, alignment);
    }

    public PrintStreamDecoderTreeVisitor(final PrintStream stream) {
        this(stream, 0);
    }

    public PrintStreamDecoderTreeVisitor(final PrintStream stream, final int alignment) {
        this.stream = stream;
        this.maxDepth = alignment;
    }

    @Override
    public DecoderTreeSwitchVisitor visitSwitch() {
        return new SwitchVisitor(0, 0, 0);
    }

    @Override
    public DecoderTreeBranchVisitor visitBranch() {
        return new BranchVisitor(0, 0, 0);
    }

    @Override
    public DecoderTreeLeafVisitor visitInstruction() {
        return new LeafVisitor(0, 0, 0, ~0, true);
    }

    @Override
    public void visitEnd() {
    }

    private void printNodeHeader(final int depth, final int branchMask, final boolean hasChildren, final boolean isLastChild) {
        for (int branch = (1 << depth); branch != 0; branch = branch >>> 1) {
            if (branch == 1) {
                if (isLastChild) {
                    stream.print("└");
                } else {
                    stream.print("├");
                }
                if (hasChildren) {
                    stream.print("─┬─");
                    for (int j = depth + 1; j < maxDepth; j++) { // +1 because we already printed 2 more chars.
                        stream.print("──");
                    }
                } else {
                    stream.print("─");
                    for (int j = depth; j < maxDepth; j++) {
                        stream.print("──");
                    }
                }
                stream.print("╴ ");
            } else if ((branch & branchMask) != 0) {
                stream.print("│ ");
            } else {
                stream.print("  ");
            }
        }
    }

    private static char[] formatMasked(final int value, final int mask, final int consumedMask) {
        final char[] chars = StringUtils.leftPad(Integer.toBinaryString(value), 32, '0').toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if ((mask & (1 << i)) == 0) {
                chars[chars.length - 1 - i] = '.';
            }
            if ((consumedMask & (1 << i)) != 0) {
                chars[chars.length - 1 - i] = ' ';
            }
        }
        return chars;
    }

    private static int instructionSizeToMask(final int size) {
        return BitUtils.maskFromRange(0, size * 8 - 1);
    }

    private final class SwitchVisitor implements DecoderTreeSwitchVisitor {
        private final int depth;
        private final int consumedMask;
        private final int branchMask;
        private int count;

        public SwitchVisitor(final int depth, final int consumedMask, final int branchMask) {
            this.depth = depth;
            this.consumedMask = consumedMask;
            this.branchMask = branchMask;
        }

        @Override
        public void visit(final DecoderTreeSwitchNode node) {
            this.count = node.children.length;
        }

        @Override
        public DecoderTreeVisitor visitSwitchCase(final DecoderTreeSwitchNode node, final int index) {
            final boolean isLastChild = index == count - 1;
            return new InnerNodeVisitor(depth + 1, consumedMask, (branchMask << 1) | (isLastChild ? 0 : 1), node.mask(), formatMasked(node.children[index].pattern(), node.mask(), consumedMask), isLastChild);
        }

        @Override
        public void visitEnd() {
        }
    }

    private final class BranchVisitor implements DecoderTreeBranchVisitor {
        private final int depth;
        private final int consumedMask;
        private final int branchMask;
        private int count;

        public BranchVisitor(final int depth, final int consumedMask, final int branchMask) {
            this.depth = depth;
            this.consumedMask = consumedMask;
            this.branchMask = branchMask;
        }

        @Override
        public void visit(final int count) {
            this.count = count;
        }

        @Override
        public DecoderTreeVisitor visitBranchCase(final int index, final int mask, final int pattern) {
            final boolean isLastChild = index == count - 1;
            return new InnerNodeVisitor(depth + 1, consumedMask, (branchMask << 1) | (isLastChild ? 0 : 1), mask, formatMasked(pattern, mask, consumedMask), isLastChild);
        }

        @Override
        public void visitEnd() {
        }
    }

    private final class InnerNodeVisitor implements DecoderTreeVisitor {
        private final int depth;
        private final int consumedMask;
        private final int branchMask;
        private final int parentMask;
        private final boolean isLastChild;
        private final char[] pattern;

        public InnerNodeVisitor(final int depth, final int consumedMask, final int branchMask, final int parentMask, final char[] pattern, final boolean isLastChild) {
            this.depth = depth;
            this.consumedMask = consumedMask;
            this.branchMask = branchMask;
            this.parentMask = parentMask;
            this.pattern = pattern;
            this.isLastChild = isLastChild;
        }

        @Override
        public DecoderTreeSwitchVisitor visitSwitch() {
            printNodeHeader(depth, branchMask, true, isLastChild);
            System.out.print(pattern);
            System.out.println("    [SWITCH]");

            return new SwitchVisitor(depth, consumedMask | parentMask, branchMask);
        }

        @Override
        public DecoderTreeBranchVisitor visitBranch() {
            printNodeHeader(depth, branchMask, true, isLastChild);
            stream.print(pattern);
            stream.println("    [BRANCH]");

            return new BranchVisitor(depth, consumedMask | parentMask, branchMask);
        }

        @Override
        public DecoderTreeLeafVisitor visitInstruction() {
            return new LeafVisitor(depth, consumedMask, branchMask, parentMask, isLastChild);
        }

        @Override
        public void visitEnd() {
        }
    }

    private final class LeafVisitor implements DecoderTreeLeafVisitor {
        private final int depth;
        private final int consumedMask;
        private final int branchMask;
        private final int parentMask;
        private final boolean isLastChild;

        public LeafVisitor(final int depth, final int consumedMask, final int branchMask, final int parentMask, final boolean isLastChild) {
            this.depth = depth;
            this.consumedMask = consumedMask;
            this.branchMask = branchMask;
            this.parentMask = parentMask;
            this.isLastChild = isLastChild;
        }

        @Override
        public void visitInstruction(final InstructionDeclaration declaration) {
            printNodeHeader(depth, branchMask, false, isLastChild);
            stream.print(formatMasked(declaration.pattern, parentMask & declaration.patternMask, consumedMask | ~instructionSizeToMask(declaration.size)));
            stream.print("    ");
            stream.println(declaration.displayName);
        }

        @Override
        public void visitEnd() {
        }
    }
}
