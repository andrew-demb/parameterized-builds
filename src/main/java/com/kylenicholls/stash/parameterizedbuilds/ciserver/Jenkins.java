package com.kylenicholls.stash.parameterizedbuilds.ciserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.atlassian.bitbucket.project.ProjectService;
import com.atlassian.bitbucket.user.ApplicationUser;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.kylenicholls.stash.parameterizedbuilds.item.BitbucketVariables;
import com.kylenicholls.stash.parameterizedbuilds.item.JenkinsResponse;
import com.kylenicholls.stash.parameterizedbuilds.item.Job;
import com.kylenicholls.stash.parameterizedbuilds.item.Server;
import com.kylenicholls.stash.parameterizedbuilds.item.UserToken;
import com.kylenicholls.stash.parameterizedbuilds.item.JenkinsResponse.JenkinsMessage;

public class Jenkins {
    private static final Logger logger = LoggerFactory.getLogger(Jenkins.class);
    private static final String PLUGIN_KEY = "com.kylenicholls.stash.parameterized-builds";
    private static final String JENKINS_SETTINGS = ".jenkinsSettings";
    private static final String JENKINS_SETTINGS_PROJECT = JENKINS_SETTINGS + ".";
    private static final String JENKINS_USER = ".jenkinsUser.";
    private final PluginSettings pluginSettings;

    public Jenkins(PluginSettingsFactory factory) {
        this.pluginSettings = factory.createSettingsForKey(PLUGIN_KEY);
    }

    /**
     * Saves or removes a Jenkins server for a specfic project. If the server is
     * null then the global server will be removed for the project.
     *
     * @param server
     *            the project server
     * @param projectKey
     *            the project key
     */
    public void saveJenkinsServer(@Nullable Server server, String projectKey) {
        if (projectKey == null || projectKey == ""){
            saveJenkinsServerToDB(JENKINS_SETTINGS, server);
        } else {
            saveJenkinsServerToDB(JENKINS_SETTINGS_PROJECT + projectKey, server);
        }
    }

    /**
     * Saves or removes a Jenkins server depending on the url parameter. If the
     * url parameter is null then the Jenkins server will be removed.
     *
     * @param key
     *            key to save the Jenkins server under, should be global or a
     *            project key
     * @param server
     *            the server
     */
    private void saveJenkinsServerToDB(String key, @Nullable Server server) {
        if (server != null) {
            pluginSettings.put(key, server.asMap());
        } else {
            pluginSettings.remove(key);
        }
    }

    /**
     * Saves or removes a Jenkins user token. If the token parameter is empty
     * then the Jenkins user token will be removed.
     *
     * @param userSlug
     *            the user slug to save the token under
     * @param projectKey
     *            if the projectKey is empty then the user token is saved as a
     *            global token, else the user token is saved as a project token
     * @param token
     *            the Jenkins api token, if the token is empty then the setting
     *            will be removed
     */
    public void saveUserToken(String userSlug, String projectKey, String token) {
        String appendProject = projectKey.isEmpty() ? "" : "." + projectKey;
        if (!token.isEmpty()) {
            pluginSettings.put(JENKINS_USER + userSlug + appendProject, token);
        } else {
            pluginSettings.remove(JENKINS_USER + userSlug + appendProject);
        }
    }

    /**
     * Returns the global Jenkins server.
     *
     * @return the global Jenkins server or null if there is not one
     */
    @Nullable
    private Server getJenkinsServer() {
        Object settingObj = pluginSettings.get(JENKINS_SETTINGS);
        if (settingObj != null) {
            if (settingObj instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> serverMap = (Map<String, Object>) settingObj;
                return new Server(serverMap);
            }
            // legacy settings
            String[] serverProps = settingObj.toString().split(";");
            boolean altUrl = serverProps.length > 3 && "true".equals(serverProps[3]) ? true : false;
            boolean csrfEnabled = true;
            return new Server(serverProps[0], null, serverProps[1], serverProps[2], altUrl, 
                    csrfEnabled);
        }
        return null;
    }

    /**
     * Returns a Jenkins server for a project.
     *
     * @return a Jenkins server for a project or null if there is not one for
     *         the specified project
     */
    @Nullable
    public Server getJenkinsServer(String projectKey) {
        if (projectKey == null || projectKey.equals("global-settings")) {
            return getJenkinsServer();
        }

        Object settingObj = pluginSettings.get(JENKINS_SETTINGS_PROJECT + projectKey);
        if (settingObj != null && settingObj instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> serverMap = (Map<String, Object>) settingObj;
            return new Server(serverMap);
        }
        return null;
    }

    /**
     * Returns the colon separated global user token for the specified user.
     *
     * @return the colon separated user token or null if the user is null or the
     *         token does not exist
     * @param user
     *            the user to get the token for, can be null if the user is
     *            anonymous
     */
    @Nullable
    private String getJoinedUserToken(@Nullable ApplicationUser user) {
        String userToken = getUserToken(user, null);
        if (userToken != null) {
            return user.getSlug() + ":" + userToken;
        }
        return null;
    }

    /**
     * Returns the colon separated user token for the specified user and
     * project.
     *
     * @return the colon separated user token or null if the user is null or the
     *         token does not exist
     * @param user
     *            the user to get the token for, can be null if the user is
     *            anonymous
     * @param projectKey
     *            the project to get the token for
     */
    @Nullable
    public String getJoinedUserToken(@Nullable ApplicationUser user, String projectKey) {
        if (projectKey == null || projectKey.equals("global-settings")) {
            return getJoinedUserToken(user);
        }

        String userToken = getUserToken(user, projectKey);
        if (userToken != null) {
            return user.getSlug() + ":" + userToken;
        }
        return null;
    }

    /**
     * Returns the user token for the specified user and project.
     *
     * @return the user token or null if the user is null or the token does not
     *         exist
     * @param user
     *            the user to get the token for, can be null if the user is
     *            anonymous
     * @param projectKey
     *            the project to get the token for, if null then this returns
     *            the global user token
     */
    @Nullable
    private String getUserToken(@Nullable ApplicationUser user, @Nullable String projectKey) {
        if (user != null) {
            if (projectKey == null) {
                Object settingObj = pluginSettings.get(JENKINS_USER + user.getSlug());
                if (settingObj != null) {
                    return settingObj.toString();
                }
            } else {
                Object settingObj = pluginSettings
                        .get(JENKINS_USER + user.getSlug() + "." + projectKey);
                if (settingObj != null) {
                    return settingObj.toString();
                }
            }
        }
        return null;
    }

    /**
     * Returns a list of all user tokens for all projects (including global)
     * that have a Jenkins server set.
     *
     * @return a list of all user tokens for all projects (including global)
     *         that have a Jenkins server set.
     * @param user
     *            the user to get the token for
     * @param projectKeys
     *            all the project keys in the bitbucket server instance
     * @param projectService
     *            used to get the project name
     */
    protected List<UserToken> getAllUserTokens(ApplicationUser user, List<String> projectKeys,
            ProjectService projectService) {
        List<UserToken> userTokens = new ArrayList<>();
        String globalUserTokenString = getUserToken(user, null);
        Server globalServer = getJenkinsServer();
        if (globalServer != null) {
            UserToken globalUserToken = new UserToken(globalServer.getBaseUrl(), 
                    globalServer.getAlias(), "", "Global", user.getSlug(), globalUserTokenString);
            userTokens.add(globalUserToken);
        }

        for (String projectKey : projectKeys) {
            Server projectServer = getJenkinsServer(projectKey);
            String projectUserTokenString = getUserToken(user, projectKey);
            if (projectServer != null) {
                UserToken projectUserToken = new UserToken(projectServer.getBaseUrl(), 
                        projectServer.getAlias(), projectKey, 
                        projectService.getByKey(projectKey).getName(), user.getSlug(),
                        projectUserTokenString);
                userTokens.add(projectUserToken);
            }
        }
        return userTokens;
    }

    /**
     * Returns a message object from the triggered job.
     *
     * @return a message object from the triggered job.
     * @param buildUrl
     *            the build url to trigger
     * @param joinedToken
     *            the authentication token to use in the request
     * @param csrfHeader
     *            the token to use in case cross site protection is enabled
     * @param promptUser
     *            prompt the user to link their jenkins account
     */
    public JenkinsResponse sanitizeTrigger(@Nullable String buildUrl, @Nullable String joinedToken,
            @Nullable String csrfHeader, boolean promptUser) {
        if (buildUrl == null) {
            return new JenkinsResponse.JenkinsMessage().error(true)
                    .messageText("Jenkins settings are not setup").build();
        }

        return httpPost(buildUrl.replace(" ", "%20"), joinedToken, csrfHeader, promptUser);
    }

    public JenkinsResponse triggerJob(String projectKey, ApplicationUser user, Job job, 
                                      BitbucketVariables bitbucketVariables) {
        Server jenkinsServer;
        String joinedUserToken;
        if (job.getJenkinsServer() != null){
            jenkinsServer = getJenkinsServer(job.getJenkinsServer());
            joinedUserToken = getJoinedUserToken(user, job.getJenkinsServer());
        } else {
            // legacy behaviour
            Server projectServer = getJenkinsServer(projectKey);
            if (projectServer != null){
                jenkinsServer = projectServer;
                joinedUserToken = getJoinedUserToken(user, projectKey);
            } else {
                jenkinsServer = getJenkinsServer(null);
                joinedUserToken = getJoinedUserToken(user);
            }
        }

        String buildUrl = job
                .buildUrl(jenkinsServer, bitbucketVariables, joinedUserToken != null);

        // use default user and token if the user that triggered the
        // build does not have a token set
        boolean prompt = false;
        if (joinedUserToken == null) {
            prompt = true;
            if (!jenkinsServer.getUser().isEmpty()) {
                joinedUserToken = jenkinsServer.getJoinedToken();
            }
        }

        String csrfHeader = null;
        if (jenkinsServer.getCsrfEnabled()) {
            // get a CSRF token because cross site protection is enabled
            try {
                csrfHeader = getCrumb(jenkinsServer.getBaseUrl(), joinedUserToken);
            } catch(Exception e){
                logger.warn("error getting CSRF token");
            }
        }

        return sanitizeTrigger(buildUrl, joinedUserToken, csrfHeader, prompt);
    }

    private HttpURLConnection setupConnection(String baseUrl, String userToken) throws Exception{
        URL url = new URL(baseUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (userToken != null && !userToken.isEmpty()) {
            byte[] authEncBytes = Base64.encodeBase64(userToken.getBytes());
            String authStringEnc = new String(authEncBytes);
            connection.setRequestProperty("Authorization", "Basic " + authStringEnc);
        }
        connection.setReadTimeout(45000);
        connection.setInstanceFollowRedirects(true);
        connection.setDoOutput(true);
        HttpURLConnection.setFollowRedirects(true);
        return connection;
    }

    public String testConnection(Server server){
        try {
            String url = server.getBaseUrl().replaceAll("/$", "") + "/api/json";
            HttpURLConnection connection = setupConnection(url, server.getJoinedToken());
            connection.setRequestMethod("GET");
            connection.setFixedLengthStreamingMode(0);

            String csrfHeader = null;
            if (server.getCsrfEnabled()) {
                // get a CSRF token because cross site protection is enabled
                try {
                    csrfHeader = getCrumb(server.getBaseUrl(), server.getJoinedToken());
                } catch(Exception e){
                    logger.warn("error getting CSRF token");
                }
            }

            if (csrfHeader != null){
                String[] header = csrfHeader.split(":");
                connection.setRequestProperty(header[0], header[1]);
            }

            connection.connect();

            int status = connection.getResponseCode();
            if (status == 403){
                return "Error authenticating to server";
            } else if (status == 200) {
                return "Connection successful";
            } else {
                return "Failed to establish connection";
            }
        } catch (Exception e) {
            return "An error occurred trying to establish a connection";
        }
    }

    private String getCrumb(String baseUrl, String token) throws Exception{
        final String crumbPath = "/crumbIssuer/api/xml?xpath=" + 
                                 "concat(//crumbRequestField,\":\",//crumb)";
        final String crumbUrl = baseUrl  + crumbPath;
        // workaround temporary javax.net.ssl.SSLException: Received close_notify during handshake
        // retry the connection three times should be OK for temporary connection issues
        final int maxRetries = 3;
        final int sleepRetryMS = 3000;
        for( int retry = 1; retry <= maxRetries; ++retry ) {
            try {
                final HttpURLConnection connection = setupConnection(crumbUrl, token);
                connection.connect();
                final int status = connection.getResponseCode();
                if (status == 200) {
                    return new BufferedReader(new InputStreamReader(
                            (connection.getInputStream()))).readLine();
                } else {
                    logger.warn("Could not connect to " + baseUrl +
                                ", got HTTP status " + status + ".");
                    return null;
                }
            } catch(final SSLException e) {
                if( retry < maxRetries ) {
                    // log issue and try again
                    logger.warn("Could not connect to " + baseUrl +
                                ", will retry in " + sleepRetryMS + "ms", e);
                } else {
                    throw e;
                }
            }
            // wait before next retry
            Thread.sleep(sleepRetryMS);
        }
        return null;
    }

    private JenkinsResponse httpPost(String buildUrl, String token, String csrfHeader, 
                                     boolean prompt) {
        JenkinsMessage jenkinsMessage = new JenkinsResponse.JenkinsMessage().prompt(prompt);
        try {
            HttpURLConnection connection = setupConnection(buildUrl, token);
            connection.setRequestMethod("POST");
            connection.setFixedLengthStreamingMode(0);

            if (csrfHeader != null){
                String[] header = csrfHeader.split(":");
                connection.setRequestProperty(header[0], header[1]);
            }
            connection.connect();

            int status = connection.getResponseCode();
            if (status == 201) {
                return jenkinsMessage.messageText("Build triggered").build();
            }

            String message;
            String responseMessage =  connection.getResponseMessage();
            if (status == 403) {
                message = "You do not have permissions to build this job";
            } else if (status == 302 && responseMessage.equals("Found")) {
                //multibranch pipelines cause redirects on the build but work just fine
                //so if we get a redirect but it is successful, just report success
                return jenkinsMessage.messageText("Build triggered").build();
            } else if (status == 404) {
                message = "Job was not found";
                return jenkinsMessage.error(true).messageText(message).build();
            } else if (status == 500) {
                message = "Error triggering job, invalid build parameters";
            } else {
                message = responseMessage;
            }
            logger.error("Exception for parametized build: " + message);
            return jenkinsMessage.error(true).messageText(message).build();
        } catch (MalformedURLException e) {
            return jenkinsMessage.error(true).messageText("Malformed URL: " + e.getMessage())
                    .build();
        } catch (IOException e) {
            logger.error("IOException in Jenkins.httpPost: " + e.getMessage(), e);
            return jenkinsMessage.error(true).messageText("IO exception occurred: " + 
                                                          e.getMessage())
                    .build();
        } catch (Exception e) {
            logger.error("Exception in Jenkins.httpPost: " + e.getMessage(), e);
            return jenkinsMessage.error(true).messageText("Something went wrong: " + e.getMessage())
                    .build();
        }
    }
}
