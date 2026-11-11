package com.apkstudio.app;

import com.android.apksig.ApkSigner;

import java.io.*;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

final class Signer {
    /** ডিফল্ট key (PKCS#8 DER + X.509 DER) দিয়ে সাইন। */
    static void signDefault(File in, File out, InputStream key, InputStream cert, int minSdk) throws Exception {
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(drain(key)));
        X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(cert);
        sign(in, out, pk, c, minSdk);
    }

    /** ইউজারের PKCS12 keystore (.p12/.pfx) দিয়ে সাইন। */
    static void signKeystore(File in, File out, File ks, String alias, String pass, String keyPass, int minSdk) throws Exception {
        KeyStore k = KeyStore.getInstance("PKCS12");
        FileInputStream f = new FileInputStream(ks);
        try { k.load(f, pass.toCharArray()); } finally { f.close(); }
        if (alias == null || alias.length() == 0) alias = k.aliases().nextElement();
        PrivateKey pk = (PrivateKey) k.getKey(alias, (keyPass == null || keyPass.length() == 0 ? pass : keyPass).toCharArray());
        if (pk == null) throw new IOException("alias '" + alias + "' এ key পাওয়া যায়নি");
        sign(in, out, pk, (X509Certificate) k.getCertificate(alias), minSdk);
    }

    private static void sign(File in, File out, PrivateKey pk, X509Certificate c, int minSdk) throws Exception {
        List<X509Certificate> certs = new ArrayList<X509Certificate>();
        certs.add(c);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("APKSTUDIO", pk, certs).build();
        List<ApkSigner.SignerConfig> l = new ArrayList<ApkSigner.SignerConfig>();
        l.add(sc);
        new ApkSigner.Builder(l).setInputApk(in).setOutputApk(out).setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true).setCreatedBy("APK Studio").build().sign();
    }

    static byte[] drain(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toByteArray();
    }
}
