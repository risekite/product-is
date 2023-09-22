package org.wso2.identity.integration.test.oidc;

import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.TokenErrorResponse;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.http.HTTPResponse;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.token.OIDCTokens;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Lookup;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.cookie.CookieSpecProvider;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.cookie.RFC6265CookieSpecProvider;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.identity.integration.test.oidc.bean.OIDCApplication;
import org.wso2.identity.integration.test.rest.api.user.common.model.Email;
import org.wso2.identity.integration.test.rest.api.user.common.model.Name;
import org.wso2.identity.integration.test.rest.api.user.common.model.UserObject;
import org.wso2.identity.integration.test.utils.DataExtractUtil;
import org.wso2.identity.integration.test.utils.OAuth2Constant;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This test class tests the OIDC RP-Initiated logout flows
 */
public class OIDCRPInitiatedLogoutTestCase extends OIDCAbstractIntegrationTest {

    protected UserObject user;
    protected String idToken;
    protected String sessionDataKeyConsent;
    protected String sessionDataKey;
    protected AuthorizationCode authorizationCode;
    CookieStore cookieStore = new BasicCookieStore();
    protected Lookup<CookieSpecProvider> cookieSpecRegistry;
    protected RequestConfig requestConfig;
    protected HttpClient client;
    protected List<NameValuePair> consentParameters = new ArrayList<>();
    OIDCApplication playgroundApp;

    @BeforeClass(alwaysRun = true)
    public void testInit() throws Exception {

        super.init();

        initUser();
        createUser(user);
        userInfo.setUserName(user.getUserName());
        userInfo.setPassword(user.getPassword());

        playgroundApp = initApplication();
        createApplication(playgroundApp);

        cookieSpecRegistry = RegistryBuilder.<CookieSpecProvider>create()
                .register(CookieSpecs.DEFAULT, new RFC6265CookieSpecProvider())
                .build();
        requestConfig = RequestConfig.custom()
                .setCookieSpec(CookieSpecs.DEFAULT)
                .build();
        client = HttpClientBuilder.create().setDefaultCookieStore(cookieStore)
                .setDefaultCookieSpecRegistry(cookieSpecRegistry)
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    @AfterClass(alwaysRun = true)
    public void testClear() throws Exception {

        deleteUser(user);
        deleteApplication(playgroundApp);
        clear();
    }

    @AfterMethod
    public void clearVariables() {

        sessionDataKey = null;
        sessionDataKeyConsent = null;
    }

    @Test(groups = "wso2.is", description = "Test RP-initiated logout with client_id parameter")
    public void testOIDCLogoutWithClientId() throws Exception {

        testInitiateOIDCRequest(playgroundApp, client);
        testOIDCLogin(playgroundApp, true);
        testOIDCConsentApproval(playgroundApp);
        testOIDCLogout(new BasicNameValuePair("client_id", playgroundApp.getClientId()));
    }

    @Test(groups = "wso2.is", description = "Test RP-initiated logout with id_token_hint parameter",
            dependsOnMethods = { "testOIDCLogoutWithClientId" })
    public void testOIDCLogoutWithIdTokenHint() throws Exception {

        testInitiateOIDCRequest(playgroundApp, client);
        testOIDCLogin(playgroundApp, false);
        testGetIdToken();
        testOIDCLogout(new BasicNameValuePair("id_token_hint", idToken));
    }

    private void testInitiateOIDCRequest(OIDCApplication application, HttpClient client) throws Exception {

        List<NameValuePair> urlParameters = OIDCUtilTest.getNameValuePairs(application);
        HttpResponse response = sendPostRequestWithParameters(client, urlParameters, String.format
                (OIDCUtilTest.targetApplicationUrl, application.getApplicationContext() +
                        OAuth2Constant.PlaygroundAppPaths.appUserAuthorizePath));
        Assert.assertNotNull(response, "Authorization request failed for " + application.getApplicationName() +
                ". Authorized response is null.");

        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);

        Assert.assertNotNull(locationHeader, "Authorization request failed for " +
                application.getApplicationName() + ". Authorized response header is null.");
        EntityUtils.consume(response.getEntity());

        response = sendGetRequest(client, locationHeader.getValue());
        Assert.assertNotNull(response, "Authorization request failed for " +
                application.getApplicationName() + ". Authorized user response is null.");

        Map<String, Integer> keyPositionMap = new HashMap<>(1);
        keyPositionMap.put("name=\"sessionDataKey\"", 1);
        List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractDataFromResponse(response,
                keyPositionMap);
        Assert.assertNotNull(keyValues, "The sessionDataKey value is null for " +
                application.getApplicationName());

        sessionDataKey = keyValues.get(0).getValue();
        Assert.assertNotNull(sessionDataKey, "Invalid sessionDataKey for " + application.getApplicationName());

        EntityUtils.consume(response.getEntity());
    }

    private void testOIDCLogin(OIDCApplication application, boolean checkConsent) throws Exception {

        HttpResponse response = sendLoginPost(client, sessionDataKey);
        Assert.assertNotNull(response, "Login request failed for " + application.getApplicationName() +
                ". response is null.");

        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
        Assert.assertNotNull(locationHeader, "Login response header is null for " +
                application.getApplicationName());
        EntityUtils.consume(response.getEntity());

        response = sendGetRequest(client, locationHeader.getValue());
        Map<String, Integer> keyPositionMap = new HashMap<>(1);
        if (checkConsent) {
            keyPositionMap.put("name=\"sessionDataKeyConsent\"", 1);
            List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractSessionConsentDataFromResponse(
                    response, keyPositionMap);
            Assert.assertNotNull(keyValues, "SessionDataKeyConsent keyValues map is null.");
            sessionDataKeyConsent = keyValues.get(0).getValue();
            Assert.assertNotNull(sessionDataKeyConsent, "sessionDataKeyConsent is null.");
        } else {
            keyPositionMap.put("Authorization Code", 1);
            List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractTableRowDataFromResponse(response,
                    keyPositionMap);
            Assert.assertNotNull(keyValues, "Authorization code not received for " +
                    application.getApplicationName());

            authorizationCode = new AuthorizationCode(keyValues.get(0).getValue());
            Assert.assertNotNull(authorizationCode, "Authorization code not received for " + application
                    .getApplicationName());
        }
        EntityUtils.consume(response.getEntity());
    }

    private void testOIDCConsentApproval(OIDCApplication application) throws Exception {

        HttpResponse response = sendApprovalPostWithConsent(client, sessionDataKeyConsent, consentParameters);
        Assert.assertNotNull(response, "Approval request failed for " + application.getApplicationName() + ". "
                + "response is invalid.");

        Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
        Assert.assertNotNull(locationHeader, "Approval request failed for " + application.getApplicationName()
                + ". Location header is null.");
        EntityUtils.consume(response.getEntity());

        response = sendPostRequest(client, locationHeader.getValue());
        Assert.assertNotNull(response, "Authorization code response is invalid for " +
                application.getApplicationName());

        Map<String, Integer> keyPositionMap = new HashMap<>(1);
        keyPositionMap.put("Authorization Code", 1);
        List<DataExtractUtil.KeyValue> keyValues = DataExtractUtil.extractTableRowDataFromResponse(response,
                keyPositionMap);
        Assert.assertNotNull(keyValues, "Authorization code not received for " +
                application.getApplicationName());

        authorizationCode = new AuthorizationCode(keyValues.get(0).getValue());
        Assert.assertNotNull(authorizationCode, "Authorization code not received for " + application
                .getApplicationName());
        EntityUtils.consume(response.getEntity());
    }

    private void testGetIdToken() throws Exception {

        ClientID clientID = new ClientID(playgroundApp.getClientId());
        Secret clientSecret = new Secret(playgroundApp.getClientSecret());
        ClientSecretBasic clientSecretBasic = new ClientSecretBasic(clientID, clientSecret);

        URI callbackURI = new URI(playgroundApp.getCallBackURL());
        AuthorizationCodeGrant authorizationCodeGrant = new AuthorizationCodeGrant(authorizationCode, callbackURI);

        TokenRequest tokenReq = new TokenRequest(new URI(OAuth2Constant.ACCESS_TOKEN_ENDPOINT), clientSecretBasic,
                authorizationCodeGrant);

        HTTPResponse tokenHTTPResp = tokenReq.toHTTPRequest().send();
        Assert.assertNotNull(tokenHTTPResp, "Access token http response is null.");

        TokenResponse tokenResponse = OIDCTokenResponseParser.parse(tokenHTTPResp);
        Assert.assertNotNull(tokenResponse, "Access token response is null.");

        Assert.assertFalse(tokenResponse instanceof TokenErrorResponse,
                "Access token response contains errors.");

        OIDCTokenResponse oidcTokenResponse = (OIDCTokenResponse) tokenResponse;
        OIDCTokens oidcTokens = oidcTokenResponse.getOIDCTokens();
        Assert.assertNotNull(oidcTokens, "OIDC Tokens object is null.");

        idToken = oidcTokens.getIDTokenString();
        Assert.assertNotNull(idToken, "ID token is null");
    }

    private void testOIDCLogout(BasicNameValuePair parameter) {

        try {
            String oidcLogoutUrl = identityContextUrls.getWebAppURLHttps() + "/oidc/logout?" + parameter.getName() +
                    "=" + parameter.getValue() + "&post_logout_redirect_uri=" + playgroundApp.getCallBackURL();
            HttpResponse response = sendGetRequest(client, oidcLogoutUrl);
            EntityUtils.consume(response.getEntity());

            List<NameValuePair> urlParameters = new ArrayList<>();
            urlParameters.add(new BasicNameValuePair("consent", "approve"));
            response = sendPostRequestWithParameters(client, urlParameters, oidcLogoutUrl);
            Header locationHeader = response.getFirstHeader(OAuth2Constant.HTTP_RESPONSE_HEADER_LOCATION);
            EntityUtils.consume(response.getEntity());

            String redirectUrl = locationHeader.getValue();
            Assert.assertTrue(redirectUrl.contains(playgroundApp.getCallBackURL()), "Not redirected to the" +
                    "post logout redirect url");
            response = sendGetRequest(client, redirectUrl);
            Assert.assertNotNull(response, "OIDC Logout failed.");
            String result = DataExtractUtil.getContentData(response);
            Assert.assertTrue(result.contains("WSO2 OAuth2 Playground"), "OIDC logout failed.");
            EntityUtils.consume(response.getEntity());
        } catch (Exception e) {
            Assert.fail("OIDC Logout failed.", e);
        }
    }

    protected void initUser() {

        user = new UserObject();
        user.setUserName(OIDCUtilTest.username);
        user.setPassword(OIDCUtilTest.password);
        user.setName(new Name().givenName(OIDCUtilTest.firstName).familyName(OIDCUtilTest.lastName));
        user.addEmail(new Email().value(OIDCUtilTest.email));
    }

    protected OIDCApplication initApplication() {

        playgroundApp = new OIDCApplication(OIDCUtilTest.playgroundAppOneAppName,
                OIDCUtilTest.playgroundAppOneAppContext,
                OIDCUtilTest.playgroundAppOneAppCallBackUri);
        playgroundApp.addRequiredClaim(OIDCUtilTest.emailClaimUri);
        playgroundApp.addRequiredClaim(OIDCUtilTest.firstNameClaimUri);
        return playgroundApp;
    }
}
