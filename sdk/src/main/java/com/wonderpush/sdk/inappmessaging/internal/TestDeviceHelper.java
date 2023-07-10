// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.wonderpush.sdk.inappmessaging.internal;

import com.wonderpush.sdk.inappmessaging.model.Campaign;

import java.util.List;

import javax.inject.Inject;

/**
 * Determines whether the app is a fresh install or the device is in test mode. Exposes methods to
 * check for install freshness and test device status as well as a method to update these fields by
 * processing a campaign fetch response.
 *
 * @hide
 */
public class TestDeviceHelper {

  /*@VisibleForTesting*/ static final String FRESH_INSTALL_PREFERENCES = "fresh_install";
  /*@VisibleForTesting*/ static final int MAX_FETCH_COUNT = 5;

  private final SharedPreferencesUtils sharedPreferencesUtils;
  private boolean isFreshInstall;
  private int fetchCount = 0;

  @Inject
  public TestDeviceHelper(SharedPreferencesUtils sharedPreferencesUtils) {
    this.sharedPreferencesUtils = sharedPreferencesUtils;
    this.isFreshInstall = readFreshInstallStatusFromPreferences();
  }

  /**
   * Determine whether app was just installed.
   *
   * @return true if this is a fresh install
   */
  public boolean isAppInstallFresh() {
    return isFreshInstall;
  }

  /**
   * Updates test device status based on a response from the IAM server.
   *
   * @param campaigns campaign fetch response from the IAM server.
   */
  public void processCampaignFetch(List<Campaign> campaigns) {
    updateFreshInstallStatus();
  }

  /** Increments the fetch count which is used to determine if an app install is fresh. */
  private void updateFreshInstallStatus() {
    // We only care about this logic if we are a fresh install.
    if (isFreshInstall) {
      fetchCount += 1;
      if (fetchCount >= MAX_FETCH_COUNT) {
        setFreshInstallStatus(false);
      }
    }
  }

  /**
   * Sets the app fresh install state and saves it into the app stored preferences
   *
   * @param isEnabled whether or not the app is a fresh install.
   */
  private void setFreshInstallStatus(boolean isEnabled) {
    isFreshInstall = isEnabled;
    // Update SharedPreferences, so that we preserve state across app restarts
    sharedPreferencesUtils.setBooleanPreference(FRESH_INSTALL_PREFERENCES, isEnabled);
  }

  /**
   * Reads the fresh install status from the apps stored preferences. Defaults to true because apps
   * start out as fresh installs.
   *
   * @return true if the app is a fresh install.
   */
  private boolean readFreshInstallStatusFromPreferences() {
    return sharedPreferencesUtils.getAndSetBooleanPreference(FRESH_INSTALL_PREFERENCES, true);
  }
}
