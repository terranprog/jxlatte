package com.thebombzen.jxlatte;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import com.thebombzen.jxlatte.io.Loggers;
import com.thebombzen.jxlatte.io.PFMWriter;
import com.thebombzen.jxlatte.io.PNGWriter;
import com.thebombzen.jxlatte.util.functional.ExceptionalConsumer;

public class JXLatte {

    public static final String JXLATTE_VERSION = "0.1.0";

    private static void writeImage(ExceptionalConsumer<? super OutputStream> writer,
            String outputFilename) throws IOException {
        boolean stdout = outputFilename.equals("-");
        OutputStream out = new BufferedOutputStream(stdout ? System.out : new FileOutputStream(outputFilename));
        try {
            writer.accept(out);
            out.flush();
        } finally {
            if (!outputFilename.equals("-"))
                out.close();
        }
    }

    private static void writePNG(String outputFilename, JXLImage image, JXLOptions options) throws IOException {
        boolean hdr = options.hdr == JXLOptions.HDR_AUTO ? image.isHDR() : options.hdr == JXLOptions.HDR_ON;
        int bitDepth = hdr ? 16 : options.outputDepth;
        PNGWriter writer = new PNGWriter(image, bitDepth, options.outputCompression, hdr, options.peakDetect);
        writeImage(writer::write, outputFilename);
    }

    private static void writePFM(String outputFilename, JXLImage image, JXLOptions options) throws IOException {
        PFMWriter writer = new PFMWriter(image);
        writeImage(writer::write, outputFilename);
    }

    private static void usage(boolean success) {
        String[] lines = new String[]{
            "jxlatte, version: " + JXLATTE_VERSION,
            "Usage: java -jar jxlatte.jar [options...] [--] <input.jxl> [output]",
            "",
            "Options: ",
            "    --help",
            "        print this message",
            "    --info",
            "        output info about the input file",
            "    --info=verbose, --verbose, --v",
            "        be more verbose with info",
            "    --debug",
            "        turn on debugging output",
            "",
            "    --format=<png|pfm>",
            "        write image in this output format",
            "    --png-depth=N",
            "        use N-bit output for PNG, N must be 8 or 16",
            "    --png-compression=N",
            "        use zlib level N to compress PNG output, N must be 0-9",
            "    --png-hdr",
            "        output PNG files in HDR",
            "        (BT.2020 Primaries, D65 White Point, PQ Transfer)",
            "    --png-peak-detect",
            "        Run peak detection when writing SDR PNGs",
            "",
            "    --draw-varblocks",
            "        Show varblocks for VarDCT images",
            "    --threads=N",
            "        Use N threads (0 for auto)",
            "",
            "If the output filename is not provided, jxlatte will discard the decoded pixels.",
        };
        System.err.println(String.join(String.format("%n"), lines));
        System.exit(success ? 0 : 1);
    }

    private static boolean parseOption(JXLOptions options, String arg, boolean foundMM) {
        if (foundMM || !arg.startsWith("--")) {
            if (options.input == null) {
                options.input = arg;
                return false;
            }
            if (options.output == null) {
                options.output = arg;
                return false;
            }
            System.err.format("jxlatte: Invalid trailing argument: %s%n", arg);
            System.exit(1);
        }
        if (arg.equals("--"))
            return true;
        int indexEq = arg.indexOf("=");
        String key = indexEq >= 0 ? arg.substring(2, indexEq) : arg.substring(2);
        String value = indexEq >= 0 ? arg.substring(indexEq + 1) : "";
        String keyL = key.toLowerCase();
        String valueL = value.toLowerCase();
        switch (keyL) {
            case "":
                return true;
            case "help":
                usage(true);
                return true;
            case "format":
                if (valueL.equals("png")) {
                    options.outputFormat = JXLOptions.OUTPUT_PNG;
                } else if (valueL.equals("pfm")) {
                    options.outputFormat = JXLOptions.OUTPUT_PFM;
                } else {
                    System.err.format("jxlatte: Unknown --format: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "debug":
                if (Arrays.asList("", "yes", "true").contains(valueL)) {
                    options.debug = true;
                } else if (Arrays.asList("no", "false").contains(valueL)) {
                    options.debug = false;
                } else {
                    System.err.format("jxlatte: Unknown --debug flag: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "png-hdr":
                if (Arrays.asList("", "auto").contains(valueL)) {
                    options.hdr = JXLOptions.HDR_AUTO;
                } else if (Arrays.asList("yes", "true", "hdr").contains(valueL)) {
                    options.hdr = JXLOptions.HDR_ON;
                } else if (Arrays.asList("no", "false", "sdr").contains(valueL)) {
                    options.hdr = JXLOptions.HDR_OFF;
                } else {
                    System.err.format("jxlatte: Unknown --png-hdr flag: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "png-depth":
                try {
                    options.outputDepth = Integer.parseInt(valueL);
                } catch (NumberFormatException nfe) {
                    System.err.format("jxlatte: Not an integer: %s%n", value);
                    System.exit(1);
                }
                if (options.outputDepth != 8 && options.outputDepth != 16) {
                    System.err.println("jxlatte: Only 8-bit and 16-bit outputs supported in PNG");
                    System.exit(1);
                }
                return true;
            case "png-compression":
                try {
                    options.outputCompression = Integer.parseInt(valueL);
                } catch (NumberFormatException nfe) {
                    System.err.format("jxlatte: Not an integer: %s%n", value);
                    System.exit(1);
                }
                if (options.outputCompression < 0 || options.outputCompression > 9) {
                    System.err.println("jxlatte: Only compression 0-9 supported in PNG");
                    System.exit(1);
                }
                return true;
            case "png-peak-detect":
                if (Arrays.asList("", "auto").contains(valueL)) {
                    options.peakDetect = JXLOptions.PEAK_DETECT_AUTO;
                } else if (Arrays.asList("yes", "true").contains(valueL)) {
                    options.peakDetect = JXLOptions.PEAK_DETECT_ON;
                } else if (Arrays.asList("no", "false").contains(valueL)) {
                    options.peakDetect = JXLOptions.PEAK_DETECT_OFF;
                } else {
                    System.err.format("jxlatte: Unknown --png-peak-detect flag: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "info":
                if (Arrays.asList("", "info", "yes", "true").contains(valueL)) {
                    options.verbosity = Loggers.LOG_INFO;
                } else if (Arrays.asList("no", "false").contains(valueL)) {
                    options.verbosity = Loggers.LOG_BASE;
                } else if (Arrays.asList("v", "verbose").contains(valueL)) {
                    options.verbosity = Loggers.LOG_VERBOSE;
                } else if (valueL.equals("trace")) {
                    options.verbosity = Loggers.LOG_TRACE;
                } else {
                    System.err.format("jxlatte: Unknown --info flag: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "verbose":
                if (Arrays.asList("", "yes", "true", "v", "verbose").contains(valueL)) {
                    options.verbosity = Loggers.LOG_VERBOSE;
                } else if (Arrays.asList("no", "false").contains(valueL)) {
                    options.verbosity = Loggers.LOG_BASE;
                } else {
                    System.err.format("jxlatte: Unknown --verbose flag: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "draw-varblocks":
                if (Arrays.asList("", "yes", "true").contains(valueL)) {
                    options.renderVarblocks = true;
                } else if (Arrays.asList("no", "false").contains(valueL)) {
                    options.renderVarblocks = false;
                } else {
                    System.err.format("jxlatte: Unknown --draw-varblocks flag: %s%n", value);
                    System.exit(1);
                }
                return true;
            case "threads":
                try {
                    options.threads = Integer.parseInt(valueL);
                } catch (NumberFormatException nfe) {
                    System.err.format("jxlatte: Not an integer: %s%n", value);
                    System.exit(1);
                }
                if (options.threads < 0 || options.threads > 65536) {
                    System.err.format("jxlatte: Illegal number of threads: %s%n", value);
                    System.exit(1);
                }
                return true;
            default:
                System.err.format("jxlatte: Unknown arg: %s%n", arg);
                System.exit(1);
                return true;
        }
    }

    private static JXLOptions parseOptions(String... args) {
        if (args.length == 0)
            usage(false);
        JXLOptions options = new JXLOptions();
        boolean foundMM = false;
        for (String arg : args) {
            parseOption(options, arg, foundMM);
            if (arg.equals("--"))
                foundMM = true;
        }

        if (options.input == null) {
            System.err.format("jxlatte: Missing input filename%n%n");
            usage(false);
        }

        if (options.output != null && options.outputFormat == JXLOptions.OUTPUT_DEFAULT) {
            int idx = options.output.lastIndexOf('.');
            if (idx >= 0) {
                String ext = options.output.substring(idx + 1).toLowerCase();
                if (ext.equals("pfm")) {
                    options.outputFormat = JXLOptions.OUTPUT_PFM;
                } else if (ext.equals("png")) {
                    options.outputFormat = JXLOptions.OUTPUT_PNG;
                }
            }
        }

        if (options.output != null && options.outputFormat == JXLOptions.OUTPUT_DEFAULT) {
            System.err.println("jxlatte: Unable to determine output format from file extension.");
            System.exit(1);
        }

        return options;
    }

    private static boolean writeImage(JXLOptions options, JXLDecoder decoder) {
        JXLImage image = null;
        try {
            image = decoder.decode();
        } catch (EOFException | InvalidBitstreamException ex) {
            System.err.println("jxlatte: Invalid input bitstream");
            if (options.debug)
                ex.printStackTrace();
            System.exit(3);
        } catch (IOException ioe) {
            System.err.println("jxlatte: I/O error occurred");
            if (options.debug)
                ioe.printStackTrace();
            System.exit(2);
        } catch (UnsupportedOperationException uoe) {
            System.err.format("jxlatte: %s%n", uoe.getMessage());
            if (options.debug)
                uoe.printStackTrace();
            System.exit(4);
        } catch (Exception re) {
            System.err.println("jxlatte: BUG: " + re.getMessage());
            re.printStackTrace();
            System.exit(4);
        }

        if (image == null)
            return false;

        if (options.output != null) {
            try {
                if (options.outputFormat == JXLOptions.OUTPUT_PFM) {
                    System.err.println("Decoded to pixels, writing PFM output.");
                    writePFM(options.output, image, options);
                } else if (options.outputFormat == JXLOptions.OUTPUT_PNG) {
                    System.err.println("Decoded to pixels, writing PNG output.");
                    writePNG(options.output, image, options);
                }
            } catch (FileNotFoundException fnfe) {
                System.err.println("jxlatte: Could not open output file for writing");
                if (options.debug)
                    fnfe.printStackTrace();
                System.exit(2);
            } catch (IOException ioe) {
                System.err.println("jxlatte: Error writing output file");
                if (options.debug)
                    ioe.printStackTrace();
                System.exit(2);
            }
        } else {
            System.err.println("Decoded to pixels, discarding output.");
        }

        return true;
    }

    public static void main(String[] args) {
        JXLOptions options = parseOptions(args);

        JXLDecoder decoder = null;
        try {
            decoder = options.input.equals("-")
                ? new JXLDecoder(System.in, options)
                : new JXLDecoder(options.input, options);
        } catch (FileNotFoundException ex) {
            System.err.format("jxlatte: Unable to open file: %s%n", options.input);
            System.exit(2);
        }

        while (writeImage(options, decoder)) {
            // pass
        }

        try {
            decoder.close();
        } catch (IOException ioe) {
            if (options.debug)
                ioe.printStackTrace();
            System.exit(2);
        }

        if (options.output != null && options.output.equals("-")) {
            System.out.close();
        }
    }

    private JXLatte() {

    }
}
