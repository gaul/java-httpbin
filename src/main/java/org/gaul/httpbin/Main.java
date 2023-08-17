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

import java.net.URI;

public final class Main {
    private Main() {
        throw new AssertionError("intentionally not implemented");
    }

    public static void main(String[] args) throws Exception {
        // TODO: configurable
        URI httpBinEndpoint = URI.create("http://127.0.0.1:8080");

        HttpBin httpBin = new HttpBin(httpBinEndpoint);
        httpBin.start();
    }
}
