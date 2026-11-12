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

    /** কোন স্ট্রিং (needle সহ) কোন ক্লাস/মেথডে ব্যবহার হয়েছে। মান: "ক্লাস#মেথড|কল১,কল২" বা "ক্লাস#ফিল্ড:নাম"। ব্যর্থ হলে ফাঁকা। */
    static java.util.Map<String, List<String>> owners(byte[] dex, String needle) {
        java.util.Map<String, List<String>> out = new java.util.HashMap<String, List<String>>();
        try {
            DexBackedDexFile file = new DexBackedDexFile(Opcodes.getDefault(), dex);
            for (org.jf.dexlib2.iface.ClassDef c : file.getClasses()) {
                String cn = c.getType();
                cn = cn.length() > 2 ? cn.substring(1, cn.length() - 1).replace('/', '.') : cn;
                for (org.jf.dexlib2.iface.Field f : c.getFields()) {
                    EncodedValue v = f.getInitialValue();
                    if (v != null && v.getValueType() == ValueType.STRING) put(out, ((StringEncodedValue) v).getValue(), needle, cn + "#ফিল্ড:" + f.getName());
                }
                for (org.jf.dexlib2.iface.Method m : c.getMethods()) {
                    org.jf.dexlib2.iface.MethodImplementation impl = m.getImplementation();
                    if (impl == null) continue;
                    List<String> hits = new ArrayList<String>(), calls = new ArrayList<String>();
                    for (Instruction i : impl.getInstructions()) {
                        if (!(i instanceof org.jf.dexlib2.iface.instruction.ReferenceInstruction)) continue;
                        org.jf.dexlib2.iface.reference.Reference r = ((org.jf.dexlib2.iface.instruction.ReferenceInstruction) i).getReference();
                        if (r instanceof StringReference) {
                            String sv = ((StringReference) r).getString();
                            if (sv.contains(needle)) hits.add(sv);
                        } else if (r instanceof org.jf.dexlib2.iface.reference.MethodReference && calls.size() < 4) {
                            org.jf.dexlib2.iface.reference.MethodReference mr = (org.jf.dexlib2.iface.reference.MethodReference) r;
                            String dc = mr.getDefiningClass();
                            dc = dc.substring(dc.lastIndexOf('/') + 1, dc.length() - 1);
                            String call = dc + "." + mr.getName();
                            if (!dc.equals("Object") && !dc.startsWith("StringBuilder") && !mr.getName().equals("<init>") && !calls.contains(call)) calls.add(call);
                        }
                    }
                    if (hits.isEmpty()) continue;
                    StringBuilder sb = new StringBuilder(cn).append('#').append(m.getName()).append('|');
                    for (int k = 0; k < calls.size(); k++) sb.append(k > 0 ? "," : "").append(calls.get(k));
                    for (String sv : hits) put(out, sv, needle, sb.toString());
                }
            }
        } catch (Throwable t) { /* মালিক জানা না গেলেও স্ক্যান চলবে */ }
        return out;
    }

    private static void put(java.util.Map<String, List<String>> m, String s, String needle, String owner) {
        if (s.indexOf(needle) < 0) return;
        List<String> l = m.get(s);
        if (l == null) { l = new ArrayList<String>(); m.put(s, l); }
        if (l.size() < 6 && !l.contains(owner)) l.add(owner);
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
