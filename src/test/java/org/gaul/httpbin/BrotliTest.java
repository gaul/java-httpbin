/*
 * Copyright 2018-2023 Andrew Gaul <andrew@gaul.org>
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.Random;

import org.brotli.dec.BrotliInputStream;
import org.junit.Test;

public final class BrotliTest {
    /** A stream a decoder cannot read would defeat the endpoint. */
    @Test
    public void testDecoderReadsWhatWeWrite() throws Exception {
        // Nothing, one byte, either side of the point where a meta-block
        // fills, and several blocks past it.
        for (int length : new int[] {
            0, 1, 100, 65535, 65536, 65537, 200000,
        }) {
            byte[] data = new byte[length];
            new Random(length).nextBytes(data);
            assertThat(decode(Brotli.encode(data)))
                    .as("%d bytes", length).isEqualTo(data);
        }
    }

    /** Stored bytes cost a header per block and nothing more. */
    @Test
    public void testStoredStreamBarelyGrows() throws Exception {
        assertThat(Brotli.encode(new byte[0])).hasSize(1);
        assertThat(Brotli.encode(new byte[100])).hasSize(104);
        assertThat(Brotli.encode(new byte[65536])).hasSize(65540);
        assertThat(Brotli.encode(new byte[65537])).hasSize(65544);
    }

    private static byte[] decode(byte[] encoded) throws Exception {
        try (BrotliInputStream is = new BrotliInputStream(
                new ByteArrayInputStream(encoded))) {
            return is.readAllBytes();
        }
    }
}
