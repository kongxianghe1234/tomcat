/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.tribes.group.interceptors;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelException;
import org.apache.catalina.tribes.ChannelMessage;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.group.ChannelInterceptorBase;
import org.apache.catalina.tribes.group.InterceptorPayload;
import org.apache.catalina.tribes.io.XByteBuffer;
import org.apache.catalina.tribes.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * Adds encryption using a pre-shared key.
 *
 * The length of the key (in bytes) must be acceptable for the encryption
 * algorithm being used. For example, for AES, you must use a key of either
 * 16 bytes (128 bits, 24 bytes 192 bits), or 32 bytes (256 bits).
 *
 * You can supply the raw key bytes by calling {@link #setEncryptionKey(byte[])}
 * or the hex-encoded binary bytes by calling
 * {@link #setEncryptionKey(String)}.
 */
public class EncryptInterceptor extends ChannelInterceptorBase implements EncryptInterceptorMBean {

    private static final Log log = LogFactory.getLog(EncryptInterceptor.class);
    protected static final StringManager sm = StringManager.getManager(EncryptInterceptor.class);

    private static final String DEFAULT_ENCRYPTION_ALGORITHM = "AES/CBC/PKCS5Padding";

    private String providerName;
    private String encryptionAlgorithm = DEFAULT_ENCRYPTION_ALGORITHM;
    private byte[] encryptionKeyBytes;
    private String encryptionKeyString;

    /**
     * This is the name of the core encryption algorithm e.g. AES.
     */
    private String algorithmName;
    private SecretKeySpec secretKey;
    private ConcurrentLinkedQueue<Cipher> cipherPool;
    private ConcurrentLinkedQueue<SecureRandom> randomPool;

    public EncryptInterceptor() {
    }

    @Override
    public void start(int svc) throws ChannelException {
        if(Channel.SND_TX_SEQ == (svc & Channel.SND_TX_SEQ)) {
            initInternal();
        }

        super.start(svc);
    }

    @Override
    public void stop(int svc) throws ChannelException {
        if(Channel.SND_TX_SEQ == (svc & Channel.SND_TX_SEQ)) {
            // Individual Cipher and SecureRandom objects need no explicit teardown
            cipherPool.clear();
            randomPool.clear();
        }

        super.stop(svc);
    }

    @Override
    public void sendMessage(Member[] destination, ChannelMessage msg, InterceptorPayload payload)
            throws ChannelException {
        try {
            byte[] data = msg.getMessage().getBytes();

            // See #encrypt(byte[]) for an explanation of the return value
            byte[][] bytes = encrypt(data);

            XByteBuffer xbb = msg.getMessage();

            // Completely replace the message
            xbb.clear();
            xbb.append(bytes[0], 0, bytes[0].length);
            xbb.append(bytes[1], 0, bytes[1].length);

            super.sendMessage(destination, msg, payload);

        } catch (GeneralSecurityException gse) {
            log.error(sm.getString("encryptInterceptor.encrypt.failed"));
            throw new ChannelException(gse);
        }
    }

    @Override
    public void messageReceived(ChannelMessage msg) {
        try {
            byte[] data = msg.getMessage().getBytes();

            data = decrypt(data);

            XByteBuffer xbb = msg.getMessage();

            // Completely replace the message with the decrypted one
            xbb.clear();
            xbb.append(data, 0, data.length);

            super.messageReceived(msg);
        } catch (GeneralSecurityException gse) {
            log.error(sm.getString("encryptInterceptor.decrypt.failed"), gse);
        }
    }

    /**
     * Sets the encryption algorithm to be used for encrypting and decrypting
     * channel messages. You must specify the <code>algorithm/mode/padding</code>.
     * Information on standard algorithm names may be found in the
     * <a href="https://docs.oracle.com/javase/8/docs/technotes/guides/security/StandardNames.html">Java
     * documentation</a>.
     *
     * Default is <code>AES/CBC/PKCS5Padding</code>.
     *
     * @param algorithm The algorithm to use.
     */
    @Override
    public void setEncryptionAlgorithm(String algorithm) {
        if(null == getEncryptionAlgorithm())
            throw new IllegalStateException(sm.getString("encryptInterceptor.algorithm.required"));

        int pos = algorithm.indexOf('/');
        if(pos < 0)
            throw new IllegalArgumentException(sm.getString("encryptInterceptor.algorithm.required"));
        pos = algorithm.indexOf('/', pos + 1);
        if(pos < 0)
            throw new IllegalArgumentException(sm.getString("encryptInterceptor.algorithm.required"));

        encryptionAlgorithm = algorithm;
    }

    /**
     * Gets the encryption algorithm being used to encrypt and decrypt channel
     * messages.
     *
     * @return The algorithm being used, including the algorithm mode and padding.
     */
    @Override
    public String getEncryptionAlgorithm() {
        return encryptionAlgorithm;
    }

    /**
     * Sets the encryption key for encryption and decryption. The length of the
     * key must be appropriate for the algorithm being used.
     *
     * @param key The encryption key.
     */
    @Override
    public void setEncryptionKey(byte[] key) {
        if (null == key) {
            encryptionKeyBytes = null;
        } else {
            encryptionKeyBytes = key.clone();
        }
    }

    /**
     * Gets the encryption key being used for encryption and decryption.
     * The key is encoded using hex-encoding where e.g. the byte <code>0xab</code>
     * will be shown as "ab". The length of the string in characters will
     * be twice the length of the key in bytes.
     *
     * @param keyBytes The encryption key.
     */
    public void setEncryptionKey(String keyBytes) {
        this.encryptionKeyString = keyBytes;
        if (null == keyBytes) {
            setEncryptionKey((byte[])null);
        } else {
            setEncryptionKey(fromHexString(keyBytes.trim()));
        }
    }

    /**
     * Gets the encryption key being used for encryption and decryption.
     *
     * @return The encryption key.
     */
    @Override
    public byte[] getEncryptionKey() {
        byte[] key = getEncryptionKeyInternal();

        if(null != key)
            key = key.clone();

        return key;
    }

    private byte[] getEncryptionKeyInternal() {
        return encryptionKeyBytes;
    }

    public String getEncryptionKeyString() {
        return encryptionKeyString;
    }

    public void setEncryptionKeyString(String encryptionKeyString) {
        setEncryptionKey(encryptionKeyString);
    }

    /**
     * Sets the JCA provider name used for cryptographic activities.
     *
     * Default is the JVM platform default.
     *
     * @param provider The name of the JCA provider.
     */
    @Override
    public void setProviderName(String provider) {
        providerName = provider;
    }

    /**
     * Gets the JCA provider name used for cryptographic activities.
     *
     * Default is the JVM platform default.
     *
     * @return The name of the JCA provider.
     */
    @Override
    public String getProviderName() {
        return providerName;
    }

    private void setSecretKey(SecretKeySpec secretKey) {
        this.secretKey = secretKey;
    }

    private SecretKeySpec getSecretKey() {
        return secretKey;
    }

    private void setAlgorithmName(String algorithm) {
        algorithmName = algorithm;
    }

    private String getAlgorithmName() {
        return algorithmName;
    }

    private void initInternal() {
        if(null == getEncryptionKey())
            throw new IllegalStateException(sm.getString("encryptInterceptor.key.required"));

        String algorithm = getEncryptionAlgorithm();

        String algorithmName;
        String algorithmMode;

        // We need to break-apart the algorithm name e.g. AES/CBC/PKCS5Padding
        // take just the algorithm part.
        int pos = algorithm.indexOf('/');

        if(pos >= 0) {
            algorithmName = algorithm.substring(0, pos);
            int pos2 = algorithm.indexOf('/', pos+1);

            if(pos2 >= 0) {
                algorithmMode = algorithm.substring(pos + 1, pos2);
            } else {
                algorithmMode = "CBC";
            }
        } else {
            algorithmName  = algorithm;
            algorithmMode = "CBC";
        }

        // Note: ECB is not an appropriate mode for secure communications.
        if(!("CBC".equalsIgnoreCase(algorithmMode)
             || "OFB".equalsIgnoreCase(algorithmMode)
             || "CFB".equalsIgnoreCase(algorithmMode)))
            throw new IllegalArgumentException(sm.getString("encryptInterceptor.algorithm.unsupported-mode", algorithmMode));

        setAlgorithmName(algorithm);
        setSecretKey(new SecretKeySpec(getEncryptionKeyInternal(), algorithmName));

        cipherPool = new ConcurrentLinkedQueue<>();
        randomPool = new ConcurrentLinkedQueue<>();
    }

    private Cipher getCipher() throws GeneralSecurityException {
        Cipher cipher = cipherPool.poll();

        if(null == cipher) {
            String providerName = getProviderName();

            if(null == providerName) {
                return Cipher.getInstance(getAlgorithmName());
            } else {
                return Cipher.getInstance(getAlgorithmName(), providerName);
            }
        }

        return cipher;
    }

    private void returnCipher(Cipher cipher) {
        cipherPool.offer(cipher);
    }

    private SecureRandom getRandom() {
        SecureRandom random = randomPool.poll();

        if(null == random) {
            random = new SecureRandom();
        }

        return random;
    }

    private void returnRandom(SecureRandom random) {
        randomPool.offer(random);
    }

    /**
     * Encrypts the input <code>bytes</code> into two separate byte arrays:
     * one for the random initialization vector (IV) used for this message,
     * and the second one containing the actual encrypted payload.
     *
     * This method returns a pair of byte arrays instead of a single
     * concatenated one to reduce the number of byte buffers created
     * and copied during the whole operation -- including message re-building.
     *
     * @param bytes The data to encrypt.
     *
     * @return The IV in [0] and the encrypted data in [1].
     *
     * @throws GeneralSecurityException If the input data cannot be encrypted.
     */
    private byte[][] encrypt(byte[] bytes) throws GeneralSecurityException {
        Cipher cipher = null;
        SecureRandom random = null;

        try {
            cipher = getCipher();
            random = getRandom();

            byte[] iv = new byte[cipher.getBlockSize()];

            // Always use a random IV For cipher setup.
            // The recipient doesn't need the (matching) IV because we will always
            // pre-pad messages with the IV as a nonce.
            random.nextBytes(iv);

            IvParameterSpec IV = new IvParameterSpec(iv);

            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), IV);

            // Prepend the IV to the beginning of the encrypted data
            byte[][] data = new byte[2][];
            data[0] = iv;
            data[1] = cipher.doFinal(bytes);

            return data;
        } finally {
            if(null != cipher)
                returnCipher(cipher);
            if(null != random)
                returnRandom(random);
        }
    }

    /**
     * Decrypts the input <code>bytes</code>.
     *
     * @param bytes The data to decrypt.
     *
     * @return The decrypted data.
     *
     * @throws GeneralSecurityException If the input data cannot be decrypted.
     */
    private byte[] decrypt(byte[] bytes) throws GeneralSecurityException {
        Cipher cipher = null;

        try {
            cipher = getCipher();

            int blockSize = cipher.getBlockSize();

            // Use first-block of incoming data as IV
            IvParameterSpec IV = new IvParameterSpec(bytes, 0, blockSize);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), IV);

            // Decrypt remainder of the message.
            return cipher.doFinal(bytes, blockSize, bytes.length - blockSize);
        } finally {
            if(null != cipher)
                returnCipher(cipher);
        }
    }


    // Copied from org.apache.tomcat.util.buf.HexUtils

    private static final int[] DEC = {
        00, 01, 02, 03, 04, 05, 06, 07,  8,  9, -1, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
        -1, 10, 11, 12, 13, 14, 15,
    };


    private static int getDec(int index) {
        // Fast for correct values, slower for incorrect ones
        try {
            return DEC[index - '0'];
        } catch (ArrayIndexOutOfBoundsException ex) {
            return -1;
        }
    }


    private static byte[] fromHexString(String input) {
        if (input == null) {
            return null;
        }

        if ((input.length() & 1) == 1) {
            // Odd number of characters
            throw new IllegalArgumentException(sm.getString("hexUtils.fromHex.oddDigits"));
        }

        char[] inputChars = input.toCharArray();
        byte[] result = new byte[input.length() >> 1];
        for (int i = 0; i < result.length; i++) {
            int upperNibble = getDec(inputChars[2*i]);
            int lowerNibble =  getDec(inputChars[2*i + 1]);
            if (upperNibble < 0 || lowerNibble < 0) {
                // Non hex character
                throw new IllegalArgumentException(sm.getString("hexUtils.fromHex.nonHex"));
            }
            result[i] = (byte) ((upperNibble << 4) + lowerNibble);
        }
        return result;
    }
}
