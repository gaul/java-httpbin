/*
 * Copyright 2018-2019 Andrew Gaul <andrew@gaul.org>
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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public final class Main {
    private Main() {
        throw new AssertionError("intentionally not implemented");
    }

    public static void main(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(Option.builder("p")
                                 .longOpt("port")
                                 .hasArg()
                                 .desc("http port")
                                 .build());
        options.addOption(Option.builder("s")
                                 .longOpt("tls-port")
                                 .hasArg()
                                 .desc("https port")
                                 .build());
        options.addOption(Option.builder("ip")
                                 .hasArg()
                                 .desc("ip to listen on")
                                 .build());
        options.addOption(Option.builder("keystore")
                                 .hasArg()
                                 .required()
                                 .build());
        CommandLineParser parser = new DefaultParser();
        try {
            CommandLine cmd = parser.parse(options, args);
            int httpPort = Integer.parseInt(cmd.getOptionValue("port", "8080"));
            int httpsPort = Integer.parseInt(cmd.getOptionValue("tls-port", "8443"));
            String ip = cmd.getOptionValue("ip", "0.0.0.0");
            String keystore = cmd.getOptionValue("keystore");

            HttpBin httpBin = new HttpBin(ip, httpPort, httpsPort, keystore);
            httpBin.start();
        } catch (ParseException e) {
            System.err.println("Error parsing command line options: " + e.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp("myapp", options);
            System.exit(1);
        }
    }
}
