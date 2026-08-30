/*
 * Copyright 2018-2026 Andrew Gaul <andrew@gaul.org>
 * Copyright 2015-2016 Bounce Storage, Inc. <info@bouncestorage.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gaul.httpbin;

import java.io.ByteArrayOutputStream;

/**
 * Writes Brotli streams that store their input rather than compressing it.
 *
 * <p>RFC 7932 lets a meta-block carry its bytes literally, so a stream built
 * that way is ordinary Brotli that any decoder reads back.  It is also the
 * only kind this project can write without a dependency: the JDK ships no
 * Brotli encoder, and the ones on offer bind to a native library that every
 * consumer of this library would then have to carry, one build per platform.
 * What /brotli exists to exercise is a client's decoding path, which a
 * stored stream exercises exactly as a compressed one would.
 */
final class Brotli {
    // The most a meta-block can hold when its length occupies four nibbles.
    private static final int MAX_META_BLOCK = 65536;

    private Brotli() {
        throw new AssertionError("intentionally not implemented");
    }

    /**
     * Wraps bytes in a Brotli stream.
     *
     * @param data bytes to carry
     * @return a Brotli stream that decodes back to them
     */
    static byte[] encode(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 8);
        BitWriter bits = new BitWriter(out);
        // A single zero bit asks for the 16 bit window, which a stream that
        // never refers backwards has no use for anyway.
        bits.write(/*value=*/ 0, /*count=*/ 1);
        for (int offset = 0; offset < data.length; offset += MAX_META_BLOCK) {
            int length = Math.min(MAX_META_BLOCK, data.length - offset);
            bits.write(/*ISLAST=*/ 0, 1);
            bits.write(/*MNIBBLES=*/ 0, 2);
            bits.write(/*MLEN=*/ length - 1, 16);
            bits.write(/*ISUNCOMPRESSED=*/ 1, 1);
            // Literal bytes begin on a byte boundary.
            bits.align();
            out.write(data, offset, length);
        }
        bits.write(/*ISLAST=*/ 1, 1);
        bits.write(/*ISLASTEMPTY=*/ 1, 1);
        bits.align();
        return out.toByteArray();
    }

    /** Packs bits into bytes least significant bit first, as Brotli does. */
    private static final class BitWriter {
        private final ByteArrayOutputStream out;
        private int accumulator;
        private int filled;

        BitWriter(ByteArrayOutputStream out) {
            this.out = out;
        }

        void write(int value, int count) {
            for (int i = 0; i < count; ++i) {
                accumulator |= ((value >>> i) & 1) << filled;
                if (++filled == 8) {
                    flush();
                }
            }
        }

        /** Ends the current byte, padding it with zeroes. */
        void align() {
            if (filled != 0) {
                flush();
            }
        }

        private void flush() {
            out.write(accumulator);
            accumulator = 0;
            filled = 0;
        }
    }
}
