/*
 * This is a port of the KFDtool software made public on github under the MIT license.
 * https://github.com/omahacommsys/KFDtool/tree/master/sw/control
 *
 * Copyright 2019-2020 KFDtool, LLC
 * Copyright 2021-2023 Natalie Moore
 * Copyright 2023 Ilya Smirnov
 * Copyright 2026 Charles Bricker
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.sentinelcomms.kfdmobile.container;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import com.sentinelcomms.kfdmobile.model.CmdKeyItem;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Handles .ekc container file serialization, encryption, and decryption.
 *
 * File format (matching KFDtool.Container.ContainerUtilities):
 *   [4 bytes LE: uncompressed XML size][GZip-compressed outer container XML]
 *
 * Outer XML contains PBKDF2 parameters and a W3C XMLenc EncryptedData element.
 * CipherValue = [16-byte AES IV] || [AES-256-CBC ciphertext of inner container XML].
 */
public final class ContainerIO {

    private static final String XMLENC_NS       = "http://www.w3.org/2001/04/xmlenc#";
    private static final int PBKDF2_ITERATIONS  = 100_000;
    private static final int KEY_LENGTH_BYTES   = 32;
    private static final int SALT_LENGTH_BYTES  = 32;
    private static final int AES_IV_LENGTH      = 16;

    private ContainerIO() {}

    // ─── Public API ────────────────────────────────────────────────────────────

    public static ContainerFile newContainer() {
        return new ContainerFile();
    }

    public static ContainerFile openContainer(Context ctx, Uri uri, String password) throws Exception {
        byte[] fileBytes;
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) throw new Exception("Cannot open file");
            fileBytes = readAllBytes(is);
        }

        byte[] xmlBytes = decompress(fileBytes);
        Document outer = parseXml(new String(xmlBytes, StandardCharsets.UTF_8));

        String derivAlg = textOf(outer, "DerivationAlgorithm");
        if (!"PBKDF2".equals(derivAlg)) throw new Exception("Unsupported key derivation: " + derivAlg);

        String hashAlg = textOf(outer, "HashAlgorithm");
        if (!"SHA512".equals(hashAlg)) throw new Exception("Unsupported hash algorithm: " + hashAlg);

        byte[] salt    = Base64.decode(textOf(outer, "Salt"), Base64.DEFAULT);
        int iterations = Integer.parseInt(textOf(outer, "IterationCount"));
        int keyLen     = Integer.parseInt(textOf(outer, "KeyLength"));
        byte[] key     = pbkdf2(password, salt, iterations, keyLen);

        byte[] cipherValue = Base64.decode(textOf(outer, "CipherValue"), Base64.DEFAULT);
        if (cipherValue.length < AES_IV_LENGTH) throw new Exception("CipherValue too short");

        byte[] iv         = new byte[AES_IV_LENGTH];
        byte[] ciphertext = new byte[cipherValue.length - AES_IV_LENGTH];
        System.arraycopy(cipherValue, 0,             iv,         0, AES_IV_LENGTH);
        System.arraycopy(cipherValue, AES_IV_LENGTH, ciphertext, 0, ciphertext.length);

        try {
            return deserializeInnerContainer(
                    parseXml(new String(aesCbcDecrypt(key, iv, ciphertext), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new Exception("Decryption failed — check password", e);
        }
    }

    /**
     * Encrypt and save a container to a URI.
     * Uses the same PBKDF2 + AES-256-CBC format as the .NET application.
     */
    public static void saveContainer(Context ctx, Uri uri, ContainerFile file, String password) throws Exception {
        byte[] innerXmlBytes = serializeInnerContainer(file).getBytes(StandardCharsets.UTF_8);

        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] key  = pbkdf2(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES);

        byte[] iv   = new byte[AES_IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        byte[] ciphertext  = aesCbcEncrypt(key, iv, innerXmlBytes);

        byte[] cipherValue = new byte[AES_IV_LENGTH + ciphertext.length];
        System.arraycopy(iv,         0, cipherValue, 0,              AES_IV_LENGTH);
        System.arraycopy(ciphertext, 0, cipherValue, AES_IV_LENGTH,  ciphertext.length);
        String cipherValueB64 = Base64.encodeToString(cipherValue, Base64.NO_WRAP);

        String outerXml  = buildOuterXml(salt, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES, cipherValueB64);
        byte[] fileBytes = compress(outerXml.getBytes(StandardCharsets.UTF_8));

        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "wt")) {
            if (os == null) throw new Exception("Cannot open output stream");
            os.write(fileBytes);
        }
    }

    /** Convert container keys to CmdKeyItems for use with ManualRekeyApplication. KEK keys are skipped. */
    public static List<CmdKeyItem> toCmdKeyItems(ContainerFile file) {
        List<CmdKeyItem> out = new ArrayList<>();
        for (ContainerKey ck : file.keys) {
            if (ck.keyTypeKek) continue;
            CmdKeyItem item = new CmdKeyItem(ck.algorithmId, ck.keysetId, ck.keyId, ck.sln, hexToBytes(ck.keyHex));
            item.activeKeyset = ck.activeKeyset;
            item.kek          = ck.keyTypeKek;
            out.add(item);
        }
        return out;
    }

    // ─── Crypto helpers ────────────────────────────────────────────────────────

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, int keyLenBytes) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512");
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLenBytes * 8);
        byte[] key = factory.generateSecret(spec).getEncoded();
        spec.clearPassword();
        return key;
    }

    private static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        return cipher.doFinal(plaintext);
    }

    private static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] padded = cipher.doFinal(ciphertext);
        // .NET EncryptedXml uses ISO 10126 padding: last byte = pad length, preceding bytes random.
        // PKCS7 (used by Android-created files) is compatible: last byte is also the pad length.
        int padLen = padded[padded.length - 1] & 0xFF;
        if (padLen < 1 || padLen > 16 || padLen > padded.length)
            throw new Exception("Invalid padding length: " + padLen);
        return Arrays.copyOf(padded, padded.length - padLen);
    }

    // ─── Compression helpers (matching KFDtool.Shared.Utility) ────────────────

    /**
     * Custom format: [4 bytes LE original size][GZip data].
     * Must match KFDtool.Shared.Utility.Compress / Decompress exactly.
     */
    static byte[] compress(byte[] data) throws Exception {
        ByteArrayOutputStream gzipBuf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(gzipBuf)) {
            gz.write(data);
        }
        byte[] gzipData = gzipBuf.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(4 + gzipData.length);
        // 4-byte little-endian original length
        ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        lenBuf.putInt(data.length);
        out.write(lenBuf.array());
        out.write(gzipData);
        return out.toByteArray();
    }

    static byte[] decompress(byte[] data) throws Exception {
        if (data.length < 4) throw new Exception("Data too short to be a container file");
        int originalLen = ByteBuffer.wrap(data, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (originalLen <= 0 || originalLen > 64 * 1024 * 1024)
            throw new Exception("Invalid uncompressed size: " + originalLen);

        byte[] out = new byte[originalLen];
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data, 4, data.length - 4))) {
            int totalRead = 0;
            int n;
            while (totalRead < originalLen &&
                   (n = gz.read(out, totalRead, originalLen - totalRead)) > 0) {
                totalRead += n;
            }
        }
        return out;
    }

    // ─── XML serialization (matching .NET XmlSerializer format) ───────────────

    private static String buildOuterXml(byte[] salt, int iterations, int keyLen, String cipherValueB64)
            throws Exception {
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.newDocument();

        Element root = doc.createElement("OuterContainer");
        root.setAttribute("version", "1.0");
        doc.appendChild(root);

        Element kd = doc.createElement("KeyDerivation");
        root.appendChild(kd);
        addText(doc, kd, "DerivationAlgorithm", "PBKDF2");
        addText(doc, kd, "HashAlgorithm",        "SHA512");
        addText(doc, kd, "Salt",                 Base64.encodeToString(salt, Base64.NO_WRAP));
        addText(doc, kd, "IterationCount",        String.valueOf(iterations));
        addText(doc, kd, "KeyLength",             String.valueOf(keyLen));

        // W3C XMLenc EncryptedData element
        Element ed = doc.createElementNS(XMLENC_NS, "EncryptedData");
        ed.setAttribute("Type", XMLENC_NS + "Element");
        root.appendChild(ed);

        Element em = doc.createElementNS(XMLENC_NS, "EncryptionMethod");
        em.setAttribute("Algorithm", XMLENC_NS + "aes256-cbc");
        ed.appendChild(em);

        Element cd = doc.createElementNS(XMLENC_NS, "CipherData");
        ed.appendChild(cd);

        Element cv = doc.createElementNS(XMLENC_NS, "CipherValue");
        cv.setTextContent(cipherValueB64);
        cd.appendChild(cv);

        return docToString(doc);
    }

    private static String serializeInnerContainer(ContainerFile file) throws Exception {
        DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        Document doc = db.newDocument();

        Element root = doc.createElement("InnerContainer");
        root.setAttribute("version", "1.0");
        doc.appendChild(root);

        Element keysEl = doc.createElement("Keys");
        root.appendChild(keysEl);
        for (ContainerKey ck : file.keys) {
            Element ki = doc.createElement("KeyItem");
            keysEl.appendChild(ki);
            addText(doc, ki, "Id",           String.valueOf(ck.id));
            addText(doc, ki, "Name",         ck.name != null ? ck.name : "");
            addText(doc, ki, "ActiveKeyset", boolStr(ck.activeKeyset));
            addText(doc, ki, "KeysetId",     String.valueOf(ck.keysetId));
            addText(doc, ki, "Sln",          String.valueOf(ck.sln));
            addText(doc, ki, "KeyTypeAuto",  boolStr(ck.keyTypeAuto));
            addText(doc, ki, "KeyTypeTek",   boolStr(ck.keyTypeTek));
            addText(doc, ki, "KeyTypeKek",   boolStr(ck.keyTypeKek));
            addText(doc, ki, "KeyId",        String.valueOf(ck.keyId));
            addText(doc, ki, "AlgorithmId",  String.valueOf(ck.algorithmId));
            addText(doc, ki, "Key",          ck.keyHex != null ? ck.keyHex.toUpperCase() : "");
        }

        addText(doc, root, "NextKeyNumber", String.valueOf(file.nextKeyNumber));

        Element groupsEl = doc.createElement("Groups");
        root.appendChild(groupsEl);
        for (ContainerGroup cg : file.groups) {
            Element gi = doc.createElement("GroupItem");
            groupsEl.appendChild(gi);
            addText(doc, gi, "Id",   String.valueOf(cg.id));
            addText(doc, gi, "Name", cg.name != null ? cg.name : "");
            Element keysListEl = doc.createElement("Keys");
            gi.appendChild(keysListEl);
            for (int keyId : cg.keys) {
                addText(doc, keysListEl, "int", String.valueOf(keyId));
            }
        }

        addText(doc, root, "NextGroupNumber", String.valueOf(file.nextGroupNumber));

        return docToString(doc);
    }

    private static ContainerFile deserializeInnerContainer(Document doc) throws Exception {
        ContainerFile file = new ContainerFile();

        NodeList keyItems = doc.getElementsByTagName("KeyItem");
        for (int i = 0; i < keyItems.getLength(); i++) {
            Element ki = (Element) keyItems.item(i);
            ContainerKey ck = new ContainerKey();
            ck.id           = parseInt(childText(ki, "Id"), 0);
            ck.name         = childText(ki, "Name");
            ck.activeKeyset = parseBool(childText(ki, "ActiveKeyset"));
            ck.keysetId     = parseInt(childText(ki, "KeysetId"),  0);
            ck.sln          = parseInt(childText(ki, "Sln"),       0);
            ck.keyTypeAuto  = parseBool(childText(ki, "KeyTypeAuto"));
            ck.keyTypeTek   = parseBool(childText(ki, "KeyTypeTek"));
            ck.keyTypeKek   = parseBool(childText(ki, "KeyTypeKek"));
            ck.keyId        = parseInt(childText(ki, "KeyId"),     0);
            ck.algorithmId  = parseInt(childText(ki, "AlgorithmId"), 0);
            ck.keyHex       = childText(ki, "Key");
            file.keys.add(ck);
            if (ck.id >= file.nextKeyNumber) file.nextKeyNumber = ck.id + 1;
        }

        String nextKey = textOf(doc, "NextKeyNumber");
        if (nextKey != null) {
            int nk = parseInt(nextKey, 0);
            if (nk > file.nextKeyNumber) file.nextKeyNumber = nk;
        }

        NodeList groupItems = doc.getElementsByTagName("GroupItem");
        for (int i = 0; i < groupItems.getLength(); i++) {
            Element gi = (Element) groupItems.item(i);
            ContainerGroup cg = new ContainerGroup();
            cg.id   = parseInt(childText(gi, "Id"),   0);
            cg.name = childText(gi, "Name");
            NodeList keyRefs = gi.getElementsByTagName("int");
            for (int j = 0; j < keyRefs.getLength(); j++) {
                cg.keys.add(parseInt(keyRefs.item(j).getTextContent().trim(), 0));
            }
            file.groups.add(cg);
            if (cg.id >= file.nextGroupNumber) file.nextGroupNumber = cg.id + 1;
        }

        String nextGroup = textOf(doc, "NextGroupNumber");
        if (nextGroup != null) {
            int ng = parseInt(nextGroup, 0);
            if (ng > file.nextGroupNumber) file.nextGroupNumber = ng;
        }

        return file;
    }

    // ─── XML utilities ─────────────────────────────────────────────────────────

    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        DocumentBuilder db = dbf.newDocumentBuilder();
        return db.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
    }

    private static String docToString(Document doc) throws Exception {
        Transformer tf = TransformerFactory.newInstance().newTransformer();
        tf.setOutputProperty(OutputKeys.ENCODING,  "UTF-8");
        tf.setOutputProperty(OutputKeys.INDENT,    "no");
        tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        StringWriter sw = new StringWriter();
        tf.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private static void addText(Document doc, Element parent, String tag, String value) {
        Element el = doc.createElement(tag);
        el.setTextContent(value);
        parent.appendChild(el);
    }

    // Match by local name only — handles both default-namespace and prefixed forms
    // (.NET's XmlDocument.OuterXml may emit xmlns:d2p1 prefixes for XMLenc elements)
    private static String textOf(Document doc, String tagName) {
        NodeList nl = doc.getElementsByTagNameNS("*", tagName);
        if (nl.getLength() == 0) nl = doc.getElementsByTagName(tagName); // no-namespace fallback
        if (nl.getLength() == 0) return null;
        return nl.item(0).getTextContent().trim();
    }

    private static String childText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }

    // ─── General utilities ─────────────────────────────────────────────────────

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    public static byte[] hexToBytes(String hex) {
        hex = hex.trim().replaceAll("\\s+", "").toUpperCase();
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                           |  Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    static String bytesToHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static String boolStr(boolean v) { return v ? "true" : "false"; }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
