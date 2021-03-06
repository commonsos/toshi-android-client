/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.view.activity;

import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.annotation.NonNull;

import com.toshi.R;
import com.toshi.databinding.ActivitySendBinding;
import com.toshi.model.local.ActivityResultHolder;
import com.toshi.presenter.LoaderIds;
import com.toshi.presenter.SendPresenter;
import com.toshi.presenter.factory.PresenterFactory;
import com.toshi.presenter.factory.SendPresenterFactory;

public class SendActivity extends BasePresenterActivity<SendPresenter, SendActivity> {

    public static final String ACTIVITY_RESULT = "activity_result";
    public static final String EXTRA__INTENT = "extraIntent";

    private ActivitySendBinding binding;
    private ActivityResultHolder resultHolder;
    private SendPresenter presenter;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    private void init() {
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_send);
    }

    @Override
    public void onResume() {
        super.onResume();
        tryProcessResultHolder();
    }

    public ActivitySendBinding getBinding() {
        return this.binding;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        this.resultHolder = new ActivityResultHolder(requestCode, resultCode, data);
        tryProcessResultHolder();
    }

    private void tryProcessResultHolder() {
        if (this.presenter == null || this.resultHolder == null) return;

        if (this.presenter.handleActivityResult(this.resultHolder)) {
            this.resultHolder = null;
        }
    }

    @NonNull
    @Override
    protected PresenterFactory<SendPresenter> getPresenterFactory() {
        return new SendPresenterFactory();
    }

    @Override
    protected void onPresenterPrepared(@NonNull SendPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    protected int loaderId() {
        return LoaderIds.get(this.getClass().getCanonicalName());
    }
}
