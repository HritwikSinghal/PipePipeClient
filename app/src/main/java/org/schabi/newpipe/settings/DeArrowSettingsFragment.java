package org.schabi.newpipe.settings;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.dearrow.DeArrowService;

import io.reactivex.rxjava3.schedulers.Schedulers;

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

        final Preference clearCachePreference =
                findPreference(getString(R.string.dearrow_clear_cache_key));
        assert clearCachePreference != null;
        clearCachePreference.setOnPreferenceClickListener((final Preference p) -> {
            // Clearing touches the disk (a directory listing plus one delete per cached bucket), so
            // it must not run on the main thread. Views already on screen keep the replacement they
            // are showing -- this drops the stored data, it does not undo a de-clickbaiting.
            Schedulers.io().scheduleDirect(() -> DeArrowService.getInstance().clearDiskCache());
            Toast.makeText(p.getContext(), R.string.dearrow_clear_cache_complete_notice,
                    Toast.LENGTH_SHORT).show();
            return true;
        });
    }
}
