package org.schabi.newpipe.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.preference.Preference;

import org.schabi.newpipe.R;

public class DeArrowSettingsFragment extends BasePreferenceFragment {
    @Override
    public void onCreatePreferences(final Bundle savedInstanceState, final String rootKey) {
        addPreferencesFromResourceRegistry();

        final Preference homePagePreference =
                findPreference(getString(R.string.dearrow_home_page_key));
        assert homePagePreference != null;
        homePagePreference.setOnPreferenceClickListener((final Preference p) -> {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.dearrow_homepage_url))));
            return true;
        });

        final Preference privacyPreference =
                findPreference(getString(R.string.dearrow_privacy_key));
        assert privacyPreference != null;
        privacyPreference.setOnPreferenceClickListener((final Preference p) -> {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.dearrow_privacy_policy_url))));
            return true;
        });
    }
}
