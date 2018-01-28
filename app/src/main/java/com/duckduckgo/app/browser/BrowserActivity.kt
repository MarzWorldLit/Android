/*
 * Copyright (c) 2017 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.app.browser

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import com.duckduckgo.app.bookmarks.ui.BookmarksActivity
import com.duckduckgo.app.global.DuckDuckGoActivity
import com.duckduckgo.app.global.ViewModelFactory
import com.duckduckgo.app.global.view.FireDialog
import com.duckduckgo.app.privacymonitor.ui.PrivacyDashboardActivity
import com.duckduckgo.app.settings.SettingsActivity
import com.duckduckgo.app.tabs.TabDataRepository
import com.duckduckgo.app.tabs.TabSwitcherActivity
import org.jetbrains.anko.toast
import java.util.*
import javax.inject.Inject
import javax.inject.Provider


class BrowserActivity : DuckDuckGoActivity() {

    @Inject
    lateinit var cookieManagerProvider: Provider<CookieManager>

    @Inject
    lateinit var repository: TabDataRepository

    @Inject
    lateinit var viewModelFactory: ViewModelFactory


    private lateinit var currentTab: BrowserTabFragment

    private val viewModel: BrowserViewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory).get(BrowserViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)
        createInitialTab()
        configureObservers()
        if (savedInstanceState == null) {
            consumeSharedQuery()
        }
    }

    private fun configureObservers() {
        repository.tabs.observe(this, Observer {
            it?.let { render(it) }
        })
    }

    private fun render(tabs: TabDataRepository.Tabs) {
        tabs.currentKey?.let {
            selectTab(it)
        }
    }

    private fun createInitialTab() {
        val tabId = UUID.randomUUID().toString()
        val fragment = BrowserTabFragment.newInstance(tabId)
        val fragmentManager = supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        fragmentTransaction.replace(R.id.fragmentContainer, fragment, tabId)
        fragmentTransaction.commit()
        currentTab = fragment
    }

    private fun createTab() {
        val tabId = UUID.randomUUID().toString()
        val fragment = BrowserTabFragment.newInstance(tabId)
        val fragmentManager = supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        if (currentTab != null) {
            fragmentTransaction.hide(currentTab)
        }
        fragmentTransaction.add(R.id.fragmentContainer, fragment, tabId)
        fragmentTransaction.commit()
        currentTab = fragment
    }

    private fun selectTab(tabId: String) {
        val fragment = supportFragmentManager.findFragmentByTag(tabId) as? BrowserTabFragment ?: return
        val fragmentManager = supportFragmentManager
        val fragmentTransaction = fragmentManager.beginTransaction()
        if (currentTab != null) {
            fragmentTransaction.hide(currentTab)
        }
        fragmentTransaction.show(fragment)
        fragmentTransaction.commit()
        currentTab = fragment
    }

    private fun consumeSharedQuery() {
        val sharedText = intent.getStringExtra(BrowserActivity.QUERY_EXTRA)
        if (sharedText != null) {
            viewModel.onSharedTextReceived(sharedText)
        }
    }

    fun launchPrivacyDashboard() {
        startActivityForResult(
            PrivacyDashboardActivity.intent(this, currentTab.tabId),
            DASHBOARD_REQUEST_CODE
        )
    }

    fun launchFire() {
        FireDialog(
            context = this,
            clearStarted = { finishActivityAnimated() },
            clearComplete = { applicationContext.toast(R.string.fireDataCleared) },
            cookieManager = cookieManagerProvider.get()
        ).show()
    }

    fun launchTabs() {
        startActivity(TabSwitcherActivity.intent(this))
    }

    fun launchNewTab() {
        createTab()
    }

    fun launchSettings() {
        startActivity(SettingsActivity.intent(this))
    }

    fun launchBookmarks() {
        startActivity(BookmarksActivity.intent(this))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == DASHBOARD_REQUEST_CODE) {
            viewModel.receivedDashboardResult(resultCode)
        }
    }

    override fun onBackPressed() {
        if (currentTab.goBack()) {
            return
        }
        currentTab.clearViewPriorToAnimation()
        super.onBackPressed()
    }

    private fun finishActivityAnimated() {
        currentTab.clearViewPriorToAnimation()
        supportFinishAfterTransition()
    }

    companion object {

        fun intent(context: Context, queryExtra: String? = null): Intent {
            val intent = Intent(context, BrowserActivity::class.java)
            intent.putExtra(QUERY_EXTRA, queryExtra)
            return intent
        }

        private const val QUERY_EXTRA = "QUERY_EXTRA"
        private const val DASHBOARD_REQUEST_CODE = 100
    }

}