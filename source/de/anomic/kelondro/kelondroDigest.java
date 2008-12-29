// kelondroDigest.java
// -----------------------
// (C) 2008 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 28.12.2008 on http://yacy.net
// this uses methods that had been implemented in serverCodings
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.kelondro;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

public class kelondroDigest {

    public static String encodeHex(final long in, final int length) {
        String s = Long.toHexString(in);
        while (s.length() < length) s = "0" + s;
        return s;
    }
    
    public static String encodeOctal(final byte[] in) {
        if (in == null) return "";
        final StringBuilder result = new StringBuilder(in.length * 8 / 3);
        for (int i = 0; i < in.length; i++) {
            if ((0Xff & in[i]) < 8) result.append('0');
            result.append(Integer.toOctalString(0Xff & in[i]));
        }
        return new String(result);
    }
    
    public static String encodeHex(final byte[] in) {
        if (in == null) return "";
        final StringBuilder result = new StringBuilder(in.length * 2);
        for (int i = 0; i < in.length; i++) {
            if ((0Xff & in[i]) < 16) result.append('0');
            result.append(Integer.toHexString(0Xff & in[i]));
        }
        return new String(result);
    }

    public static byte[] decodeHex(final String hex) {
        final byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) (16 * Integer.parseInt(hex.charAt(i * 2) + "", 16) + Integer.parseInt(hex.charAt(i * 2 + 1) + "", 16));
        }
        return result;
    }
    
    public static String encodeMD5Hex(final String key) {
        // generate a hex representation from the md5 of a string
        return encodeHex(encodeMD5Raw(key));
    }

    public static String encodeMD5Hex(final File file) {
        // generate a hex representation from the md5 of a file
        return encodeHex(encodeMD5Raw(file));
    }

    public static String encodeMD5Hex(final byte[] b) {
        // generate a hex representation from the md5 of a byte-array
        return encodeHex(encodeMD5Raw(b));
    }

    public static byte[] encodeMD5Raw(final String key) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.reset();
            byte[] keyBytes;
            try {
                keyBytes = key.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                keyBytes = key.getBytes();
            }
            digest.update(keyBytes);
            return digest.digest();
        } catch (final java.security.NoSuchAlgorithmException e) {
            System.out.println("Internal Error at md5:" + e.getMessage());
        }
        return null;
    }
    
    public static byte[] encodeMD5Raw(final File file) {
        FileInputStream  in;
        try {
            in = new FileInputStream(file);
        } catch (final java.io.FileNotFoundException e) {
            System.out.println("file not found:" + file.toString());
            e.printStackTrace();
            return null;
        }
        
        // create a concurrent thread that consumes data as it is read
        // and computes the md5 while doing IO
        md5FilechunkConsumer md5consumer = new md5FilechunkConsumer(1024 * 64, 8);
        ExecutorService service = Executors.newSingleThreadExecutor();
        Future<MessageDigest> md5result = service.submit(md5consumer);
        service.shutdown();
        
        filechunk c;
        try {
            while (true) {
                c = md5consumer.nextFree();
                c.n = in.read(c.b);
                if (c.n <= 0) break;
                md5consumer.consume(c);
            }
            in.close();
        } catch (final IOException e) {
            System.out.println("file error with " + file.toString() + ": " + e.getMessage());
            md5consumer.consume(md5FilechunkConsumer.poison);
            return null;
        }
        // put in poison into queue to tell the consumer to stop
        md5consumer.consume(md5FilechunkConsumer.poison);
        
        // return the md5 digest from future task
        try {
            return md5result.get().digest();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private static class filechunk {
        public byte[] b;
        public int n;
        public filechunk(int len) {
            b = new byte[len];
            n = 0;
        }
    }
    
    private static class md5FilechunkConsumer implements Callable<MessageDigest> {

        private BlockingQueue<filechunk> empty;
        private BlockingQueue<filechunk> filed;
        private static filechunk poison = new filechunk(0);
        private MessageDigest digest;
        
        public md5FilechunkConsumer(int bufferSize, int bufferCount) {
            empty = new ArrayBlockingQueue<filechunk>(bufferCount);
            filed = new LinkedBlockingQueue<filechunk>();
            // fill the empty queue
            for (int i = 0; i < bufferCount; i++) empty.add(new filechunk(bufferSize));
            // init digest
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                System.out.println("Internal Error at md5:" + e.getMessage());
            }
            digest.reset();
        }
        
        public void consume(filechunk c) {
            try {
                filed.put(c);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        
        public filechunk nextFree() {
            try {
                return empty.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return null;
            }
        }

        public MessageDigest call() {
            try {
                filechunk c;
                while(true) {
                    c = filed.take();
                    if (c == poison) break;
                    digest.update(c.b, 0, c.n);
                    empty.put(c);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return digest;
        }
        
    }
    
    public static String fastFingerprintHex(final File file, boolean includeDate) {
        try {
            return encodeHex(fastFingerprintRaw(file, includeDate));
        } catch (IOException e) {
            return null;
        }
    }

    public static String fastFingerprintB64(final File file, boolean includeDate) {
        try {
            return kelondroBase64Order.enhancedCoder.encode(fastFingerprintRaw(file, includeDate));
        } catch (IOException e) {
            return null;
        }
    }

    
    /**
     * the fast fingerprint computes a md5-like hash from a given file,
     * which is different from a md5 because it does not read the complete file
     * but reads only the first and last megabyte of it. In case that the
     * file is less or equal of one megabyte, the fast fingerprint is equal
     * to the md5. In other cases the fingerprint is computed from a
     * array = byte[32k + 8] array, which consists of:
     * array[0   .. 16k - 1] = first MB of file
     * array[16k .. 32k - 1] = last MB of file
     * array[32k .. 32k + 7] = length of file as long
     * if the date flag is set, the array is extended to
     * array[32k + 8 .. 32k + 15] = lastModified of file as long
     * @param file
     * @return fingerprint in md5 raw format
     * @throws IOException 
     */
    public static byte[] fastFingerprintRaw(final File file, boolean includeDate) throws IOException {
        final int mb = 16 * 1024;
        final long fl = file.length();
        if (fl <= 2 * mb) return encodeMD5Raw(file);
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
        raf.seek(0);
        byte[] a = new byte[mb];
        raf.readFully(a, 0, mb - 1);
        digest.update(a, 0, mb);
        raf.seek(fl - mb);
        raf.readFully(a, 0, mb - 1);
        digest.update(a, 0, mb);
        digest.update(kelondroNaturalOrder.encodeLong(fl, 8), 0, 8);
        if (includeDate) digest.update(kelondroNaturalOrder.encodeLong(file.lastModified(), 8), 0, 8);
        return digest.digest();
    }

    private static byte[] encodeMD5Raw(final byte[] b) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.reset();
            final InputStream  in = new ByteArrayInputStream(b);
            final byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            in.close();
            // now compute the hex-representation of the md5 digest
            return digest.digest();
        } catch (final java.security.NoSuchAlgorithmException e) {
            System.out.println("Internal Error at md5:" + e.getMessage());
        } catch (final java.io.IOException e) {
            System.out.println("byte[] error: " + e.getMessage());
        }
        return null;
    }
    
    public static void main(final String[] s) {
        // usage example:
        // java -classpath classes de.anomic.kelondro.kelondroDigest -md5 DATA/HTCACHE/mediawiki/wikipedia.de.xml
        // java -classpath classes de.anomic.kelondro.kelondroDigest -md5 readme.txt
        // java -classpath classes de.anomic.kelondro.kelondroDigest -fb64 DATA/HTCACHE/responseHeader.heap 
        // compare with:
        // md5 readme.txt
        long start = System.currentTimeMillis();
        
        if (s.length == 0) {
            System.out.println("usage: -[md5|fingerprint] <arg>");
            System.exit(0);
        }
        
        if (s[0].equals("-md5")) {
            // generate a md5 from a given file
            File f = new File(s[1]);
            System.out.println("MD5 (" + f.getName() + ") = " + encodeMD5Hex(f));
        }
        
        if (s[0].equals("-fhex")) {
            // generate a fast fingerprint from a given file
            File f = new File(s[1]);
            System.out.println("fingerprint hex (" + f.getName() + ") = " + fastFingerprintHex(f, true));
        }
        if (s[0].equals("-fb64")) {
            // generate a fast fingerprint from a given file
            File f = new File(s[1]);
            System.out.println("fingerprint b64 (" + f.getName() + ") = " + fastFingerprintB64(f, true));
        }
        System.out.println("time: " + (System.currentTimeMillis() - start) + " ms");
    }
}
