/*
 * Copyright 2021 NetKnights GmbH - nils.behlen@netknights.it
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.privacyidea.PIConstants.TOKEN_TYPE_OTP;
import static org.privacyidea.authenticator.Const.*;

class Configuration {

    private final String serverURL;
    private final String realm;
    private final boolean doSSLVerify;
    private final boolean doTriggerChallenge;
    private final boolean doSendPassword;

    private final String serviceAccountName;
    private final String serviceAccountPass;
    private final String serviceAccountRealm;
    private final List<String> excludedGroups = new ArrayList<>();
    private final boolean doEnrollToken;
    private final boolean doLog;
    private final String enrollingTokenType;
    private final List<Integer> pollingInterval = new ArrayList<>();
    private final String prefTokenType;

    Configuration(Map<String, String> configMap) {
        this.serverURL = configMap.get(CONFIG_SERVER);
        this.realm = configMap.get(CONFIG_REALM) == null ? "" : configMap.get(CONFIG_REALM);
        this.doSSLVerify = configMap.get(CONFIG_VERIFY_SSL) != null && configMap.get(CONFIG_VERIFY_SSL).equals(TRUE);
        this.doTriggerChallenge = configMap.get(CONFIG_TRIGGER_CHALLENGE) != null && configMap.get(CONFIG_TRIGGER_CHALLENGE).equals(TRUE);
        this.serviceAccountName = configMap.get(CONFIG_SERVICE_ACCOUNT) == null ? "" : configMap.get(CONFIG_SERVICE_ACCOUNT);
        this.serviceAccountPass = configMap.get(CONFIG_SERVICE_PASS) == null ? "" : configMap.get(CONFIG_SERVICE_PASS);
        this.serviceAccountRealm = configMap.get(CONFIG_SERVICE_REALM) == null ? "" : configMap.get(CONFIG_SERVICE_REALM);

        this.doEnrollToken = configMap.get(CONFIG_ENROLL_TOKEN) != null && configMap.get(CONFIG_ENROLL_TOKEN).equals(TRUE);
        this.doSendPassword = configMap.get(CONFIG_SEND_PASSWORD) != null && configMap.get(CONFIG_SEND_PASSWORD).equals(TRUE);
        // PI uses all lowercase letters for token types so change it here to match it internally
        this.prefTokenType = (configMap.get(CONFIG_PREF_TOKENTYPE) == null ? TOKEN_TYPE_OTP : configMap.get(CONFIG_PREF_TOKENTYPE)).toLowerCase();
        this.enrollingTokenType = (configMap.get(CONFIG_ENROLL_TOKENTYPE) == null ? "" : configMap.get(CONFIG_ENROLL_TOKENTYPE)).toLowerCase();

        this.doLog = configMap.get(CONFIG_DO_LOG) != null && configMap.get(CONFIG_DO_LOG).equals(TRUE);

        String excludedGroupsStr = configMap.get(CONFIG_EXCLUDED_GROUPS);
        if (excludedGroupsStr != null) {
            this.excludedGroups.addAll(Arrays.asList(excludedGroupsStr.split(",")));
        }

        // Set intervals to either default or configured values
        String s = configMap.get(CONFIG_PUSH_INTERVAL);
        if (s != null) {
            List<String> strPollingIntervals = Arrays.asList(s.split(","));
            if (!strPollingIntervals.isEmpty()) {
                this.pollingInterval.clear();
                for (String str : strPollingIntervals) {
                    try {
                        this.pollingInterval.add(Integer.parseInt(str));
                    } catch (NumberFormatException e) {
                        this.pollingInterval.add(DEFAULT_POLLING_INTERVAL);
                    }
                }
            }
        } else {
            this.pollingInterval.addAll(DEFAULT_POLLING_ARRAY);
        }
    }

    String getServerURL() {
        return serverURL;
    }

    String getRealm() {
        return realm;
    }

    boolean doSSLVerify() {
        return doSSLVerify;
    }

    boolean doTriggerChallenge() {
        return doTriggerChallenge;
    }

    String getServiceAccountName() {
        return serviceAccountName;
    }

    String getServiceAccountPass() {
        return serviceAccountPass;
    }

    String getServiceAccountRealm() {
        return serviceAccountRealm;
    }

    List<String> getExcludedGroups() {
        return excludedGroups;
    }

    boolean doEnrollToken() {
        return doEnrollToken;
    }

    String getEnrollingTokenType() {
        return enrollingTokenType;
    }

    List<Integer> getPollingInterval() {
        return pollingInterval;
    }

    boolean doLog() {
        return doLog;
    }

    boolean doSendPassword() {
        return doSendPassword;
    }

    String getPrefTokenType() {
        return prefTokenType;
    }
}
