/*
 * Copyright 2021 NetKnights GmbH - micha.preusser@netknights.it
 * nils.behlen@netknights.it
 * - Modified
 *
 * Based on original code:
 *
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.privacyidea.authenticator;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.privacyidea.*;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

import static org.privacyidea.PIConstants.PASSWORD;
import static org.privacyidea.PIConstants.TOKEN_TYPE_HOTP;
import static org.privacyidea.PIConstants.TOKEN_TYPE_PUSH;
import static org.privacyidea.PIConstants.TOKEN_TYPE_TOTP;
import static org.privacyidea.PIConstants.TOKEN_TYPE_WEBAUTHN;
import static org.privacyidea.authenticator.Const.*;

public class PrivacyIDEAAuthenticator implements org.keycloak.authentication.Authenticator, IPILogger {

    private final Logger logger = Logger.getLogger(PrivacyIDEAAuthenticator.class);

    private Configuration config;
    private PrivacyIDEA privacyIDEA;

    /**
     * This function will be called when the authentication flow triggers the privacyIDEA execution.
     * i.e. after the username + password have been submitted.
     *
     * @param context AuthenticationFlowContext
     */
    @Override
    public void authenticate(AuthenticationFlowContext context) {
        config = new Configuration(context.getAuthenticatorConfig().getConfig());

        privacyIDEA = new PrivacyIDEA.Builder(config.getServerURL(), PLUGIN_USER_AGENT)
                .setSSLVerify(config.doSSLVerify())
                .setLogger(this)
                .setPollingIntervals(config.getPollingInterval())
                .setRealm(config.getRealm())
                .setServiceAccount(config.getServiceAccountName(), config.getServiceAccountPass())
                .setServiceAccountRealm(config.getServiceAccountRealm())
                .build();

        // Get the things that were submitted in the first username+password form
        UserModel user = context.getUser();
        String currentUser = user.getUsername();
        String currentPassword = null;
        //log("[authenticate] http form params: " + context.getHttpRequest().getDecodedFormParameters().toString());
        if (context.getHttpRequest().getDecodedFormParameters().get(PASSWORD) != null) {
            currentPassword = context.getHttpRequest().getDecodedFormParameters().get(PASSWORD).get(0);
        }

        // Check if privacyIDEA is enabled for the current user
        for (GroupModel groupModel : user.getGroups()) {
            for (String excludedGroup : config.getExcludedGroups()) {
                if (excludedGroup.equals(groupModel.getName())) {
                    context.success();
                    return;
                }
            }
        }

        // Prepare for possibly triggering challenges
        PIResponse triggerResponse = null;
        String transactionID = null;
        StringBuilder pushMessage = new StringBuilder(DEFAULT_PUSH_MESSAGE);
        StringBuilder otpMessage = new StringBuilder(DEFAULT_OTP_MESSAGE);

        // Variables to configure the UI
        boolean pushAvailable = false;
        boolean otpAvailable = true; // Always assume an OTP token
        String startingMode = "otp";
        String webAuthnSignRequest = "";

        // Trigger challenges if configured. Service account has precedence over send password
        if (config.doTriggerChallenge()) {
            triggerResponse = privacyIDEA.triggerChallenges(currentUser);
        } else if (config.doSendPassword()) {
            if (currentPassword != null) {
                triggerResponse = privacyIDEA.validateCheck(currentUser, currentPassword);
            } else {
                log("Cannot send password because it is null!");
            }
        }

        // Evaluate for possibly triggered token
        if (triggerResponse != null) {
            transactionID = triggerResponse.getTransactionID();
            if (!triggerResponse.getMultiChallenge().isEmpty()) {
                pushMessage.setLength(0);
                pushMessage.append(triggerResponse
                        .getMultiChallenge()
                        .stream()
                        .filter(c -> TOKEN_TYPE_PUSH.equals(c.getType()))
                        .map(Challenge::getMessage)
                        .reduce("", (a, c) -> a + c + ", ").trim());

                if (pushMessage.length() > 0) {
                    pushMessage.deleteCharAt(pushMessage.length() - 1);
                }

                otpMessage.setLength(0);
                otpMessage.append(triggerResponse
                        .getMultiChallenge()
                        .stream()
                        .filter(c -> (TOKEN_TYPE_HOTP.equals(c.getType()) || TOKEN_TYPE_TOTP.equals(c.getType())))
                        .map(Challenge::getMessage)
                        .reduce("", (a, c) -> a + c + ", ").trim());

                if (otpMessage.length() > 0) {
                    otpMessage.deleteCharAt(otpMessage.length() - 1);
                }

                pushAvailable = triggerResponse.getMultiChallenge().stream().anyMatch(c -> TOKEN_TYPE_PUSH.equals(c.getType()));

                // Check for WebAuthnSignRequest
                // TODO currently only gets the first sign request
                if (triggerResponse.getTriggeredTokenTypes().contains(TOKEN_TYPE_WEBAUTHN)) {
                    Optional<Challenge> opt = triggerResponse.getMultiChallenge().stream().filter(c -> TOKEN_TYPE_WEBAUTHN.equals(c.getType())).findFirst();
                    if (opt.isPresent()) {
                        webAuthnSignRequest = ((WebAuthn) opt.get()).getSignRequest();
                    }
                }
            }
            // Check if any triggered token matches the preferred token type
            if (triggerResponse.getTriggeredTokenTypes().contains(config.getPrefTokenType())) {
                startingMode = config.getPrefTokenType();
            }
        }
        // Enroll token if enabled and user does not have one
        String tokenEnrollmentQR = "";
        if (config.doEnrollToken()) {
            List<TokenInfo> tokenInfos = privacyIDEA.getTokenInfo(currentUser);

            if (tokenInfos == null || tokenInfos.isEmpty()) {
                RolloutInfo rolloutInfo = privacyIDEA.tokenRollout(currentUser, config.getEnrollingTokenType());
                tokenEnrollmentQR = rolloutInfo.googleurl.img;
            }
        }
        context.getAuthenticationSession().setAuthNote(AUTH_NOTE_AUTH_COUNTER, "0");

        if (transactionID != null && !transactionID.isEmpty()) {
            context.getAuthenticationSession().setAuthNote(AUTH_NOTE_TRANSACTION_ID, transactionID);
        }

        Response responseForm = context.form()
                .setAttribute(FORM_POLL_INTERVAL, config.getPollingInterval().get(0))
                .setAttribute(FORM_TOKEN_ENROLLMENT_QR, tokenEnrollmentQR)
                .setAttribute(FORM_MODE, startingMode)
                .setAttribute(FORM_PUSH_AVAILABLE, pushAvailable)
                .setAttribute(FORM_OTP_AVAILABLE, otpAvailable)
                .setAttribute(FORM_PUSH_MESSAGE, pushMessage.toString())
                .setAttribute(FORM_OTP_MESSAGE, otpMessage.toString())
                .setAttribute(FORM_WEBAUTHN_SIGN_REQUEST, webAuthnSignRequest)
                .createForm(FORM_FILE_NAME);
        context.challenge(responseForm);
    }

    /**
     * This function will be called when our form is submitted.
     *
     * @param context AuthenticationFlowContext
     */
    @Override
    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        if (formData.containsKey("cancel")) {
            context.cancelLogin();
            return;
        }
        LoginFormsProvider form = context.form();

        /*log.info("formData:");
        formData.forEach((k, v) -> log.info("key=" + k + ", value=" + v)); */

        // Get data from form
        String tokenEnrollmentQR = formData.getFirst(FORM_TOKEN_ENROLLMENT_QR);
        String currentMode = formData.getFirst(FORM_MODE);
        boolean pushToken = TRUE.equals(formData.getFirst(FORM_PUSH_AVAILABLE));
        boolean otpToken = TRUE.equals(formData.getFirst(FORM_OTP_AVAILABLE));
        String pushMessage = formData.getFirst(FORM_PUSH_MESSAGE);
        String otpMessage = formData.getFirst(FORM_OTP_MESSAGE);
        String tokenTypeChanged = formData.getFirst(FORM_MODE_CHANGED);

        String transactionID = context.getAuthenticationSession().getAuthNote(AUTH_NOTE_TRANSACTION_ID);
        String currentUserName = context.getUser().getUsername();

        String webAuthnSignRequest = formData.getFirst(FORM_WEBAUTHN_SIGN_REQUEST);
        String webAuthnSignResponse = formData.getFirst(FORM_WEBAUTHN_SIGN_RESPONSE);

        // Prepare the failure message, the message from privacyidea will be appended if possible
        String authenticationFailureMessage = "Authentication failed.";

        // Set the "old" values again
        form.setAttribute(FORM_TOKEN_ENROLLMENT_QR, tokenEnrollmentQR)
                .setAttribute(FORM_MODE, currentMode)
                .setAttribute(FORM_PUSH_AVAILABLE, pushToken)
                .setAttribute(FORM_OTP_AVAILABLE, otpToken)
                .setAttribute(FORM_WEBAUTHN_SIGN_REQUEST, webAuthnSignRequest);

        boolean didTrigger = false; // To not show the error message if something was triggered

        if (TOKEN_TYPE_PUSH.equals(currentMode)) {
            if (privacyIDEA.pollTransaction(transactionID)) {
                PIResponse response = privacyIDEA.validateCheck(currentUserName, "", transactionID);
                if (response.getValue()) {
                    context.success();
                    return;
                }
            }
        } else if (webAuthnSignResponse != null && !webAuthnSignResponse.isEmpty()) {
            PIResponse response = privacyIDEA.validateCheckWebAuthn(currentUserName, transactionID, webAuthnSignResponse, "https://keycloak-testserver.office.netknights.it:8443");
            if (response.getValue()) {
                context.success();
                return;
            }
        } else {
            if (!(TRUE.equals(tokenTypeChanged))) {
                String otp = formData.getFirst(FORM_PI_OTP);
                // TODO add txid if present
                PIResponse response = privacyIDEA.validateCheck(currentUserName, otp);

                if (response != null) {
                    // A challenge was triggered, display its message and pass the transaction id
                    if (!response.getMultiChallenge().isEmpty()) {
                        otpMessage = response.getMessage();
                        context.getAuthenticationSession().setAuthNote(AUTH_NOTE_TRANSACTION_ID, response.getTransactionID());
                        didTrigger = true;

                        if (response.getTriggeredTokenTypes().contains(TOKEN_TYPE_PUSH)) {
                            form.setAttribute(FORM_PUSH_AVAILABLE, true);
                            // Set the message of the push token explicitly since those are 2 different UIs
                            Optional<Challenge> optChal = response.getMultiChallenge().stream().filter(c -> TOKEN_TYPE_PUSH.equals(c.getType())).findFirst();
                            if (optChal.isPresent()) {
                                pushMessage = optChal.get().getMessage();
                            }
                        }
                        // TODO add multiple challenge response
                    }

                    if (response.getValue()) {
                        context.success();
                        return;
                    }

                    authenticationFailureMessage += "\n" + response.getMessage();
                }
            }
        }

        int authCounter = Integer.parseInt(context.getAuthenticationSession().getAuthNote(AUTH_NOTE_AUTH_COUNTER)) + 1;
        authCounter = (authCounter >= config.getPollingInterval().size() ? config.getPollingInterval().size() - 1 : authCounter);
        context.getAuthenticationSession().setAuthNote(AUTH_NOTE_AUTH_COUNTER, Integer.toString(authCounter));

        // The message variables could be overwritten if a challenge was triggered. Therefore, add them here at the end
        form.setAttribute(FORM_POLL_INTERVAL, config.getPollingInterval().get(authCounter))
                .setAttribute(FORM_PUSH_MESSAGE, (pushMessage == null ? DEFAULT_PUSH_MESSAGE : pushMessage))
                .setAttribute(FORM_OTP_MESSAGE, (otpMessage == null ? DEFAULT_OTP_MESSAGE : otpMessage));

        // Dont display the error if the token type was switched
        if (!(TRUE.equals(tokenTypeChanged)) && !didTrigger) {
            form.setError(TOKEN_TYPE_PUSH.equals(currentMode) ? "Authentication not verified yet." : authenticationFailureMessage);
        }

        Response responseForm = form.createForm(FORM_FILE_NAME);
        context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS, responseForm);
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    @Override
    public void close() {
        // Just to make sure
        privacyIDEA.stopPolling();
    }

    @Override
    public void log(String message) {
        if (config.doLog()) {
            logger.info(message);
        }
    }

    @Override
    public void error(String message) {
        if (config.doLog()) {
            logger.error(message);
        }
    }

    @Override
    public void log(Throwable t) {
        if (config.doLog()) {
            logger.info(t);
        }
    }

    @Override
    public void error(Throwable t) {
        if (config.doLog()) {
            logger.error(t);
        }
    }
}
