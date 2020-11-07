/**
 * Copyright (c) 2018-2020, Sylvain Baudoin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sbaudoin.yamllint;

import org.apache.commons.cli.*;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static com.github.sbaudoin.yamllint.Format.OutputFormat;

/**
 * Main class to run YAML Lint as a command line tool. For usage, run the class as follows:
 * <pre>
 *     java -cp ... com.github.sbaudoin.yamllint.Cli -h
 * </pre>
 * or
 * <pre>
 *     java -cp ... com.github.sbaudoin.yamllint.Cli --help
 * </pre>
 */
public final class Cli {
    /**
     * The list of supported output formats
     */
    public static final Map<String, OutputFormat> OUTPUT_FORMATS = Stream.of(new Object[][] {
            { "parsable", OutputFormat.PARSABLE },
            { "standard", OutputFormat.STANDARD },
            { "colored", OutputFormat.COLORED },
            { "github", OutputFormat.GITHUB },
            { "auto", OutputFormat.AUTO },
    }).collect(Collectors.toMap(data -> (String)data[0], data -> (OutputFormat)data[1]));

    /**
     * The default output format
     */
    public static final String DEFAULT_FORMAT = "auto";

    /**
     * This application's name
     */
    public static final String APP_NAME = "yamllint";

    /**
     * Name of a local rule configuration file: if this file is present in the work directory, it is used as an extension
     * configuration file
     */
    public static final String USER_CONF_FILENAME = ".yamllint";

    /**
     * Name of the environment variable that contains the XDG base directory
     */
    public static final String XDG_CONFIG_HOME_ENV_VAR = "XDG_CONFIG_HOME";


    private static final String ARG_FILES_OR_DIR = "FILES_OR_DIR";
    private static final String ARG_CONFIG_FILE = "config_file";
    private static final String ARG_CONFIG_DATA = "config_data";
    private static final String ARG_FORMAT = "format";
    private static final String ARG_NO_WARNINGS = "no-warnings";
    private static final String ARG_STRICT = "strict";
    private static final String ARG_VERSION = "version";
    private static final String ARG_HELP = "help";


    private OutputStream stdout = System.out;
    private OutputStream errout = System.err;
    private YamlLintConfig conf;


    /**
     * Main method
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        new Cli().run(args);
    }


    /**
     * Sets the standard output stream for all messages generated by this tool
     *
     * @param out an output stream where to write standard messages
     */
    public void setStdOutputStream(OutputStream out) {
        this.stdout = out;
    }

    /**
     * Sets the standard error stream for all messages generated by this tool
     *
     * @param out an output stream where to write error messages
     */
    public void setErrOutputStream(OutputStream out) {
        this.errout = out;
    }

    /**
     * CLI entry point
     *
     * @param args the command line arguments
     */
    public void run(String[] args) {
        Map arguments = getCommandLineArguments(args);
        try {
            getYamlLintConfig(arguments);
        } catch (Exception e) {
            endOnError("cannot get or process configuration: " + e.getMessage(), false);
        }

        int maxLevel = 0;
        boolean first = true;
        for (String path : findFilesRecursively((String[])arguments.get(ARG_FILES_OR_DIR))) {
            try (InputStream in = "-".equals(path)?System.in:new FileInputStream(path)) {
                File file = new File("-".equals(path)?"stdin":path);
                // Get problems and remove warnings if requested
                List<LintProblem> problems = Linter.run(in, conf, file).stream().filter(problem -> Boolean.FALSE.equals(arguments.get(ARG_NO_WARNINGS)) ||
                        problem.getLevel() == null || Linter.ERROR_LEVEL.equals(problem.getLevel())).collect(Collectors.toList());
                String output = Format.format(file.getPath(), problems, OUTPUT_FORMATS.get(arguments.get(ARG_FORMAT)));
                if (!"".equals(output)) {
                    out(output);
                }
                // Save max level
                int level = problems.stream().mapToInt(problem -> (Integer)Linter.getProblemLevel(problem.getLevel())).max().orElse(0);
                if (level > maxLevel) {
                    maxLevel = level;
                }
            } catch (IOException e) {
                err("Cannot read " + ("-".equals(path)?"standard input":("file `" + path + "'")) + ", skipping");
            }

            // Add an extra line break for standard and colored formats
            if (!first && OUTPUT_FORMATS.get(arguments.get(ARG_FORMAT)) != OutputFormat.PARSABLE && OUTPUT_FORMATS.get(arguments.get(ARG_FORMAT)) != OutputFormat.GITHUB) {
                out("");
            }
            first = false;
        }

        if (maxLevel == (int)Linter.getProblemLevel(Linter.ERROR_LEVEL)) {
            System.exit(1);
        } else if (maxLevel == (int)Linter.getProblemLevel(Linter.WARNING_LEVEL) && Boolean.TRUE.equals(arguments.get(ARG_STRICT))) {
            System.exit(2);
        }

        System.exit(0);
    }

    /**
     * Returns a map with the options and arguments passed on the command line
     *
     * @param args the command line arguments
     * @return a map with the options and arguments passed on the command line
     */
    private Map<String, Object> getCommandLineArguments(String[] args) {
        CommandLine cmdLine = parseCommandLine(prepareOptions(), args);

        Map<String, Object> arguments = new HashMap();
        arguments.put(ARG_CONFIG_FILE, cmdLine.getOptionValue('c'));
        arguments.put(ARG_CONFIG_DATA, cmdLine.getOptionValue('d'));
        arguments.put(ARG_FORMAT, cmdLine.getOptionValue('f', DEFAULT_FORMAT));
        arguments.put(ARG_NO_WARNINGS, cmdLine.hasOption("no-warnings"));
        arguments.put(ARG_STRICT, cmdLine.hasOption('s'));
        arguments.put(ARG_FILES_OR_DIR, cmdLine.getArgs());

        return arguments;
    }

    /**
     * Defines the options of this program (excluding the last positional arguments)
     *
     * @return the options of this program
     */
    private Options prepareOptions() {
        Options options = new Options();

        OptionGroup og = new OptionGroup();
        options.addOption(Option.builder("h").longOpt(ARG_HELP).hasArg(false).argName(ARG_HELP).desc("show this help message and exit").build());
        options.addOption(Option.builder("v").longOpt(ARG_VERSION).hasArg(false).argName(ARG_VERSION).desc("show program's version number and exit").build());

        og.addOption(Option.builder("c").longOpt("config-file").hasArg().argName(ARG_CONFIG_FILE).desc("path to a custom configuration").build());
        og.addOption(Option.builder("d").longOpt("config-data").hasArg().argName(ARG_CONFIG_DATA).desc("custom configuration (as YAML source)").build());
        options.addOptionGroup(og);

        options.addOption(Option.builder("f").longOpt(ARG_FORMAT).hasArg().argName(ARG_FORMAT).desc("format for parsing output: " +
                OUTPUT_FORMATS.keySet().stream().map(f -> (DEFAULT_FORMAT.equals(f))?("'" + f + "' (default)"):("'" + f + "'")).collect(Collectors.joining(", "))).build());
        options.addOption(Option.builder().longOpt(ARG_NO_WARNINGS).hasArg(false).argName(ARG_NO_WARNINGS).desc("output only error level problems").build());
        options.addOption(Option.builder("s").longOpt(ARG_STRICT).hasArg(false).argName(ARG_STRICT).desc("return non-zero exit code on warnings as well as errors").build());

        return options;
    }

    /**
     * Parses the command line
     *
     * @param options the command line options
     * @return the parsed command line
     */
    private CommandLine parseCommandLine(Options options, String[] args) {
        CommandLineParser cmdParser = new DefaultParser();
        CommandLine cmdLine = null;
        try {
            cmdLine = cmdParser.parse(options, args);

            // Special options
            if (cmdLine.hasOption(ARG_HELP)) {
                showHelpAndExit(options, stdout, 0);
            }
            if (cmdLine.hasOption(ARG_VERSION)) {
                Properties props = new Properties();
                props.load(Cli.class.getClassLoader().getResourceAsStream("yaml.properties"));
                err(APP_NAME + " " + props.getProperty("version"));
                System.exit(0);
            }

            String format = cmdLine.getOptionValue(ARG_FORMAT, DEFAULT_FORMAT);
            if (!OUTPUT_FORMATS.containsKey(format)) {
                endOnError(
                        String.format("invalid output format '%1$s'. Supported formats: %2$s",
                                format,
                                OUTPUT_FORMATS.keySet().stream().map(f -> (DEFAULT_FORMAT.equals(f))?("'" + f + "' (default)"):("'" + f + "'")).collect(Collectors.joining(", "))),
                        false
                );
            }

            // If no argument, we show a short error message
            if (cmdLine.getArgs().length == 0) {
                endOnError("FILE_OR_DIR is required", true);
            }
            // If - is supplied, it must be the only argument
            if (Arrays.stream(cmdLine.getArgs()).anyMatch(a -> "-".equals(a)) && cmdLine.getArgs().length > 1) {
                endOnError("If - supplied, it must be the only argument", false);
            }
        } catch (AlreadySelectedException e) {
            endOnError("options `c' and `d' are mutually exclusive.\n", true);
        } catch (ParseException|IOException e) {
            endOnError(e.getMessage(), true);
        }

        return cmdLine;
    }

    private void getYamlLintConfig(Map<String, Object> arguments) throws IOException, YamlLintConfigException {

        Path userGlobalConfig;
        if (System.getenv(XDG_CONFIG_HOME_ENV_VAR) != null) {
            userGlobalConfig = Paths.get(System.getenv(XDG_CONFIG_HOME_ENV_VAR), APP_NAME, "config");
        } else {
            userGlobalConfig = Paths.get(System.getProperty("user.home"), ".config", APP_NAME, "config");
        }
        if (arguments.containsKey(ARG_CONFIG_DATA) && arguments.get(ARG_CONFIG_DATA) != null) {
            if (!"".equals(arguments.get(ARG_CONFIG_DATA)) && !((String)arguments.get(ARG_CONFIG_DATA)).contains(":")) {
                arguments.put(ARG_CONFIG_DATA, "extends: " + arguments.get(ARG_CONFIG_DATA));
            }
            conf = new YamlLintConfig((String)arguments.get(ARG_CONFIG_DATA));
        } else if (arguments.containsKey(ARG_CONFIG_FILE) && arguments.get(ARG_CONFIG_FILE) != null) {
            conf = new YamlLintConfig(new File((String)arguments.get(ARG_CONFIG_FILE)).toURI().toURL());
        } else if (fileExists(USER_CONF_FILENAME)) {
            conf = new YamlLintConfig(new File(USER_CONF_FILENAME).toURI().toURL());
        } else if (fileExists(userGlobalConfig.toString())) {
            conf = new YamlLintConfig(userGlobalConfig.toUri().toURL());
        } else {
            conf = new YamlLintConfig("extends: default");
        }
    }

    /**
     * Processes recursively the passed paths to build and return a list of expected YAML files (file extension is
     * `.yml' or `.yaml')
     *
     * @param items a list of paths
     * @return a list of paths to YAML files
     */
    private List<String> findFilesRecursively(String[] items) {
        List<String> files = new ArrayList();
        for (String item : items) {
            if ("-".equals(item)) {
                files.add("-");
                continue;
            }
            File file = new File(item);
            if (file.isDirectory()) {
                files.addAll(
                        findFilesRecursively(
                                Arrays.stream(file.list()).map(
                                        name -> file.getPath() + File.separator + name).collect(Collectors.toList()).toArray(new String[]{})));
            } else if (file.isFile() && (item.endsWith(".yml") || item.endsWith(".yaml"))) {
                files.add(item);
            }
        }
        return files;
    }

    /**
     * Tells if the passed path is a file that exists
     *
     * @param path a path
     * @return <code>true</code> if the path exists and is a file, <code>false</code> otherwise
     */
    private boolean fileExists(String path) {
        File file = new File(path);
        return file.exists() && file.isFile();
    }

    /**
     * Shows help message and exists
     *
     * @param options the options this program takes
     */
    private void showHelpAndExit(Options options, OutputStream output, int exitCode) {
        String syntax = "yamllint [-h] [-v] [-c <config_file> | -d <config_data>] [-f <format>] [--no-warnings] [-s] FILE_OR_DIR ...";
        HelpFormatter formatter = new HelpFormatter();
        // Show the options in the order they were added
        formatter.setOptionComparator((Option o1, Option o2) -> 1);
        PrintWriter pw = new PrintWriter(output);
        String termWidth = System.getenv().getOrDefault("COLUMNS", "");
        int width = Integer.parseInt("".equals(termWidth) ? "80" : termWidth);
        if (options == null) {
            formatter.printUsage(pw, width, syntax);
        } else {
            formatter.printHelp(
                    pw,
                    width,
                    syntax,
                    "\nA linter for YAML files. yamllint does not only check for syntax validity, but " +
                            "for weirdnesses like key repetition and cosmetic problems such as lines " +
                            "length, trailing spaces, indentation, etc.\n\n" +
                            "Positional arguments:\n" +
                            " FILE_OR_DIR                      the files to check or - to read from the standard input\n\n" +
                            "Optional arguments:",
                    options,
                    HelpFormatter.DEFAULT_LEFT_PAD,
                    HelpFormatter.DEFAULT_DESC_PAD,
                    null
            );
        }
        pw.flush();
        System.exit(exitCode);
    }

    /**
     * Shows an error message and terminates the program
     *
     * @param message the error message
     * @param showHelp if {@code true} a short help message will be shown after the error message
     */
    private void endOnError(String message, boolean showHelp) {
        err("Error: " + message);
        if (showHelp) {
            err("");
            showHelpAndExit(null, errout, 1);
        }
        System.exit(1);
    }

    /**
     * Writes a message to the standard output
     *
     * @param message a message
     */
    private void out(String message) {
        try {
            stdout.write(message.getBytes());
            stdout.write(System.lineSeparator().getBytes());
        } catch (IOException e) {
            e.printStackTrace(new PrintWriter(errout));
            System.exit(1);
        }
    }

    /**
     * WWrites a message to the error output
     *
     * @param message a message
     */
    private void err(String message) {
        try {
            errout.write(message.getBytes());
            errout.write(System.lineSeparator().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
