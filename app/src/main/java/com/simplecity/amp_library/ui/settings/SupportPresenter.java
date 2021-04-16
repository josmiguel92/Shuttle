package com.simplecity.amp_library.ui.settings;

import android.content.Intent;
import android.net.Uri;
import android.support.annotation.NonNull;
import com.simplecity.amp_library.BuildConfig;
import com.simplecity.amp_library.R;
import com.simplecity.amp_library.ShuttleApplication;
import com.simplecity.amp_library.ui.common.Presenter;
import com.simplecity.amp_library.utils.SettingsManager;
import com.simplecity.amp_library.utils.ShuttleUtils;
import javax.inject.Inject;

public class SupportPresenter extends Presenter<SupportView> {

    private ShuttleApplication application;

    private SettingsManager settingsManager;

    @Inject
    public SupportPresenter(ShuttleApplication application, SettingsManager settingsManager) {
        this.application = application;
        this.settingsManager = settingsManager;
    }

    @Override
    public void bindView(@NonNull SupportView view) {
        super.bindView(view);

        setAppVersion();
    }

    private void setAppVersion() {
        SupportView supportView = getView();
        if (supportView != null) {
            supportView.setVersion("MUSER " + BuildConfig.VERSION_NAME);
        }
    }

    public void faqClicked() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("http://www.shuttlemusicplayer.com/#faq"));
        SupportView supportView = getView();
        if (supportView != null) {
            supportView.showFaq(intent);
        }
    }

    public void helpClicked() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://discordapp.com/channels/499448243491569673"));
        SupportView supportView = getView();
        if (supportView != null) {
            supportView.showHelp(intent);
        }
    }

    public void emailClicked() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{application.getString(R.string.email)});
        intent.putExtra(Intent.EXTRA_SUBJECT, application.getString(R.string.subject));
        intent.setType("message/rfc822");
        SupportView supportView = getView();
        if (supportView != null) {
            supportView.openEmail(intent)               ;
        }
    }

    public void rateClicked() {

        settingsManager.setHasRated();

        SupportView supportView = getView();
        if (supportView != null) {
            Intent intent = ShuttleUtils.getShuttleStoreIntent(application.getPackageName());
            supportView.showRate(intent);
        }
    }
}