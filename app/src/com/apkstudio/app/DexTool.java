package com.apkstudio.app;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.iface.value.EncodedValue;
import org.jf.dexlib2.iface.value.StringEncodedValue;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.immutable.value.ImmutableStringEncodedValue;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.dexlib2.ValueType;

import java.util.ArrayList;
import java.util.List;

final class DexTool {
    /** dex এর সব স্ট্রিং (শুধু পড়া; দ্রুত, রিরাইট ছাড়া)। */
    static List<String> strings(byte[] b) {
        List<String> l = new ArrayList<String>();
        if (b.length < 0x70) return l;
        int n = ResPool.i32(b, 0x38), off = ResPool.i32(b, 0x3C);
        for (int i = 0; i < n; i++) {
            int p = ResPool.i32(b, off + 4 * i);
            if (p <= 0 || p >= b.length) continue;
            while ((b[p] & 0x80) != 0) p++; // ULEB128 স্কিপ
            p++;
            int e = p;
            while (e < b.length && b[e] != 0) e++;
            l.add(new String(b, p, e - p, ResPool.U8));
        }
        return l;
    }

    static byte[] rewrite(byte[] dex, final List<Engine.StrOp> ops) throws java.io.IOException {
        DexBackedDexFile file = new DexBackedDexFile(Opcodes.getDefault(), dex);
        DexRewriter rw = new DexRewriter(new RewriterModule() {
            @Override
            public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                final Rewriter<Instruction> base = super.getInstructionRewriter(rewriters);
                return new Rewriter<Instruction>() {
                    public Instruction rewrite(Instruction i) {
                        Instruction r = base.rewrite(i);
                        String name = r.getOpcode().name;
                        if (name.equals("const-string") || name.equals("const-string/jumbo")) {
                            String old = ((StringReference) ((org.jf.dexlib2.iface.instruction.ReferenceInstruction) r).getReference()).getString();
                            String nw = Engine.applyStr(old, ops);
                            if (!nw.equals(old)) {
                                int reg = ((OneRegisterInstruction) r).getRegisterA();
                                ImmutableStringReference ref = new ImmutableStringReference(nw);
                                switch (r.getOpcode().format) {
                                    case Format21c: return new ImmutableInstruction21c(r.getOpcode(), reg, ref);
                                    case Format31c: return new ImmutableInstruction31c(r.getOpcode(), reg, ref);
                                    default: break;
                                }
                            }
                        }
                        return r;
                    }
                };
            }

            @Override
            public Rewriter<EncodedValue> getEncodedValueRewriter(Rewriters rewriters) {
                final Rewriter<EncodedValue> base = super.getEncodedValueRewriter(rewriters);
                return new Rewriter<EncodedValue>() {
                    public EncodedValue rewrite(EncodedValue v) {
                        EncodedValue r = base.rewrite(v);
                        if (r.getValueType() == ValueType.STRING) {
                            String old = ((StringEncodedValue) r).getValue();
                            String nw = Engine.applyStr(old, ops);
                            if (!nw.equals(old)) return new ImmutableStringEncodedValue(nw);
                        }
                        return r;
                    }
                };
            }
        });
        DexFile out = rw.getDexFileRewriter().rewrite(file);
        MemoryDataStore ds = new MemoryDataStore(dex.length + 4096);
        DexPool.writeTo(ds, out);
        byte[] buf = ds.getBuffer();
        // MemoryDataStore বাফার বড় হতে পারে; আসল সাইজ header এর file_size থেকে
        int fsz = ResPool.i32(buf, 0x20);
        return java.util.Arrays.copyOf(buf, fsz);
    }
}
