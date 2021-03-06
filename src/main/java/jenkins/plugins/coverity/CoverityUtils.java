/*******************************************************************************
 * Copyright (c) 2017 Synopsys, Inc
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Synopsys, Inc - initial implementation and documentation
 *******************************************************************************/
package jenkins.plugins.coverity;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.EnvVars;
import hudson.model.Queue;
import hudson.remoting.Channel;
import hudson.remoting.VirtualChannel;
import hudson.util.ArgumentListBuilder;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.tools.ant.types.Commandline;

import java.io.*;
import java.lang.reflect.Array;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class CoverityUtils {

    private static final Logger logger = Logger.getLogger(CoverityUtils.class.getName());

	/**
     * Evaluates an environment variable using the specified parser. The result is an interpolated string.
     */
    public static String evaluateEnvVars(String input, EnvVars environment, boolean useAdvancedParser)throws RuntimeException{
		try{
            if(useAdvancedParser){
                String interpolated = EnvParser.interpolateRecursively(input, 1, environment);
                return interpolated;
            } else {
                return environment.expand(input);
            }
		}catch(Exception e){
			throw new RuntimeException("Error trying to evaluate environment variable: " + input);
		}
	}

    public static void checkDir(VirtualChannel channel, String home) throws Exception {
        Validate.notNull(channel, VirtualChannel.class.getName() + " object can't be null");
        Validate.notNull(home, String.class.getName() + " object can't be null");
		FilePath homePath = new FilePath(channel, home);
        if(!homePath.exists()){
            throw new Exception("Directory: " + home + " doesn't exist.");
        }
    }

	/**
	 * getCovBuild
	 *
	 * Retrieves the location of cov-build executable/sh from the system and returns the string of the
	 * path
	 * @return  string of cov-build's path
	 */
	public static String getCovBuild(TaskListener listener, Node node) {
		AbstractBuild build = getBuild();
		AbstractProject project = build.getProject();
		CoverityPublisher publisher = (CoverityPublisher) project.getPublishersList().get(CoverityPublisher.class);
		InvocationAssistance invocationAssistance = publisher.getInvocationAssistance();

        if(listener == null){
            try{
                throw new Exception("Listener used by getCovBuild() is null.");
            } catch (Exception e){
                e.printStackTrace();
                return null;
            }
        }

		String covBuild = "cov-build";
		String home = null;
		try {
			home = publisher.getDescriptor().getHome(node, build.getEnvironment(listener));
		} catch(Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            listener.getLogger().println("[Error] " + pw.toString());
			e.printStackTrace();
		}
		if(invocationAssistance != null){
			if(invocationAssistance.getSaOverride() != null) {
				try {
					home = new CoverityInstallation(invocationAssistance.getSaOverride()).forEnvironment(build.getEnvironment(listener)).getHome();
					CoverityUtils.checkDir(node.getChannel(), home);
				} catch(IOException e) {
					e.printStackTrace();
				} catch(InterruptedException e) {
					e.printStackTrace();
				} catch(Exception e) {
					e.printStackTrace();
				}
			}
		}
		if(home != null) {
			covBuild = new FilePath(node.getChannel(), home).child("bin").child(covBuild).getRemote();
		}

		return covBuild;
	}

    /**
     * Calls intepolate() in order to evaluate environment variables. In the case that a substitution took place, it
     * tokenize the result and it calls itself on each token in case further evaluations are needed.
     *
     * In the case of a recursive definition (ex: VAR1=$VAR2, VAR2=$VAR1) an exception is thrown.
     */
    public static List<String> expand(String input, EnvVars environment) throws ParseException {
        /**
         * Interpolates environment
         */
        List<String> output = new ArrayList<>();
        String interpolated = EnvParser.interpolateRecursively(input, 1, environment);
        output.addAll(EnvParser.tokenize(interpolated));
        return output;
    }

    /**
     * Evaluates environment variables on a command represented by a list of tokens.
     */
    public static List<String> evaluateEnvVars(List<String> input, EnvVars environment){
        List<String> output = new ArrayList<String>();
        try {
            for(String arg : input){
                output.addAll(expand(arg, environment));
            }
        } catch(ParseException e){
            throw new RuntimeException(e.getMessage());
        }
        return output;
    }

	/**
	 * Gets the stacktrace from an exception, so that this exception can be handled.
	 */
	public static String getStackTrace(Exception e){
		StringWriter writer = new StringWriter();
		PrintWriter printWriter = new PrintWriter( writer );
		e.printStackTrace(printWriter);
		printWriter.flush();
		String stackTrace = writer.toString();
		try {
			writer.close();
			printWriter.close();
		} catch (IOException e1) {
		}
		return stackTrace;
	}

	public static void handleException(String message, AbstractBuild<?, ?> build, BuildListener listener, Exception exception){
		listener.getLogger().println(message);
		listener.getLogger().println("Stacktrace: \n" + CoverityUtils.getStackTrace(exception));
		build.setResult(Result.FAILURE);
	}

    public static void handleException(String message, AbstractBuild<?, ?> build, TaskListener listener, Exception exception){
        listener.getLogger().println(message);
        listener.getLogger().println("Stacktrace: \n" + CoverityUtils.getStackTrace(exception));
        build.setResult(Result.FAILURE);
    }

    /**
     * Prepares command according with the specified parsing mechanism. If "useAdvancedParser" is set to true, the plugin
     * will evaluate environment variables with its custom mechanism. If not, environment variable substitution will be
     * handled by Jenkins in the standard way.
     */
    public static List<String> prepareCmds(List<String> input, String[] envVarsArray, boolean useAdvancedParser){
        if(useAdvancedParser){
            EnvVars envVars = new EnvVars(arrayToMap(envVarsArray));
            return CoverityUtils.evaluateEnvVars(input, envVars);
        } else {
            return input;
        }
    }

    public static List<String> prepareCmds(List<String> input, EnvVars envVars, boolean useAdvancedParser){
        if(useAdvancedParser){
            return CoverityUtils.evaluateEnvVars(input, envVars);
        } else {
            return input;
        }
    }

    /**
     * Jenkins API ProcStarter.envs() returns an array of environment variables where each element is a string "key=value".
     * However the constructor for EnvVars accepts only arrays of the format [key1, value1, key2, value2]. Because of
     * this, we need to transform that array into a map and use a constructor that accepts that map.
     */
    public static Map<String, String> arrayToMap(String[] input){
        Map<String, String> result = new HashMap<String, String>();
        for(int i=0; i<input.length; i++){
            List<String> keyValuePair = splitKeyValue(input[i]);
            if(keyValuePair != null && !keyValuePair.isEmpty()){
                String key = keyValuePair.get(0);
                String value = keyValuePair.get(1);
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Split string of the form key=value into an array [key, value]
     */
    public static List<String> splitKeyValue(String input){
        if(input == null){
            return null;
        }

        List<String> result = new ArrayList<>();

        int index = input.indexOf('=');
        if(index == 0){
            logger.warning("Could not parse environment variable \"" + input + "\" because its key is empty.");
        } else if (index < 0){
            logger.warning("Could not parse environment variable \"" + input + "\" because no value for it has been defined.");
        } else if(index == input.length() - 1){
            logger.warning("Could not parse environment variable \"" + input + "\" because the value for it is empty.");
        } else {
            String key = input.substring(0, index);
            String value = input.substring(index + 1);
            result.add(key);
            result.add(value);
        }

        return result;
    }

    /**
     * Returns the InvocationAssistance for a given build.
     */
    public static InvocationAssistance getInvocationAssistance(AbstractBuild<?, ?> build){
        AbstractProject project = build.getProject();
        CoverityPublisher publisher = (CoverityPublisher) project.getPublishersList().get(CoverityPublisher.class);
        return publisher.getInvocationAssistance();
    }

    /**
     * Returns the InvocationAssistance on the current thread. This can be used when an "AbstractBuild" object is not
     * available, for example while decorating the launcher.
     */
    public static InvocationAssistance getInvocationAssistance(){
        AbstractBuild build = getBuild();
        return getInvocationAssistance(build);
    }

    /**
     * Collects environment variables from an array and an EnvVars object and returns an updated EnvVars object.
     * This is useful for updating the environment variables on a ProcStarter with the variables from the listener.
     */
    public static String[] addEnvVars(String[] envVarsArray, EnvVars envVars){
        // All variables are stored on a map, the ones from ProcStarter will take precedence.
        EnvVars resultMap = new EnvVars(envVars);
        resultMap.putAll(arrayToMap(envVarsArray));

        String[] r = new String[resultMap.size()];
        int idx=0;

        for (Map.Entry<String,String> e : resultMap.entrySet()) {
            r[idx++] = e.getKey() + '=' + e.getValue();
        }
        return r;
    }

    public static int runCmd(List<String> cmd, AbstractBuild<?, ?> build, Launcher launcher, TaskListener listener,
                             EnvVars envVars, boolean useAdvancedParser) throws IOException, InterruptedException {
        /**
         * Get environment variables from a launcher, add custom environment environment variables if needed,
         * then call join() to starts the launcher process.
         */
        String[] launcherEnvVars = launcher.launch().envs();
        launcherEnvVars = CoverityUtils.addEnvVars(launcherEnvVars, envVars);
        cmd = prepareCmds(cmd, launcherEnvVars, useAdvancedParser);
        int result = launcher.
                launch().
                cmds(new ArgumentListBuilder(cmd.toArray(new String[cmd.size()]))).
                pwd(build.getWorkspace()).
                stdout(listener).
                stderr(listener.getLogger()).
                envs(launcherEnvVars).
                join();
        return result;
    }

    public static AbstractBuild getBuild(){
        Executor executor = Executor.currentExecutor();
        Queue.Executable exec = executor.getCurrentExecutable();
        AbstractBuild build = (AbstractBuild) exec;
        return build;
    }

    public  static String doubleQuote(String input){
        return "\"" + input + "\"";
    }

    /**
     * Coverity's parser remove double/single quotes but Jenkins parser does not. When dealing (for instance) with
     * streams with spaces, we would expect [--stream, My Stream]. In order to do this the token "My Stream" must be
     * quoted if using out parser, but not if using Jenkins.
     */
    public  static String doubleQuote(String input, boolean useAdvancedParser){
        if(useAdvancedParser){
            return "\"" + input + "\"";
        } else {
            return input;
        }
    }

    /**
     * Gets environment variables from the build.
     */
    public static EnvVars getBuildEnvVars(TaskListener listener){
        AbstractBuild build = CoverityUtils.getBuild();
        EnvVars envVars = null;
        try {
            envVars = build.getEnvironment(listener);
        } catch (Exception e) {
            CoverityUtils.handleException(e.getMessage(), build, listener, e);
        }
        return envVars;
    }

    /**
     * Gets environment variables from the given build
     */
    public static EnvVars getBuildEnvVars(AbstractBuild build, TaskListener listener){
        EnvVars envVars = null;
        try {
            envVars = build.getEnvironment(listener);
        } catch (Exception e) {
            CoverityUtils.handleException(e.getMessage(), build, listener, e);
        }
        return envVars;
    }

    public static Collection<File> listFiles(
            File directory,
            FilenameFilter filter,
            boolean recurse) {
        Vector<File> files = new Vector<File>();
        File[] entries = directory.listFiles();
        if (entries == null) {
            return files;
        }

        for(File entry : entries) {
            if(filter == null || filter.accept(directory, entry.getName())) {
                files.add(entry);
            }

            if(recurse && entry.isDirectory()) {
                files.addAll(listFiles(entry, filter, recurse));
            }
        }

        return files;
    }

    public static File[] listFilesAsArray(
            File directory,
            FilenameFilter filter,
            boolean recurse) {
        Collection<File> files = listFiles(directory, filter, recurse);

        File[] arr = new File[files.size()];
        return files.toArray(arr);
    }
}