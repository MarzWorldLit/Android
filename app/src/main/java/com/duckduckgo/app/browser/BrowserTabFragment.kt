/*
 * Copyright (c) 2018 DuckDuckGo
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

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProviders
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.support.v7.widget.LinearLayoutManager
import android.text.Editable
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import android.widget.Toast.makeText
import com.duckduckgo.app.bookmarks.ui.BookmarkAddEditDialogFragment
import com.duckduckgo.app.bookmarks.ui.BookmarkAddEditDialogFragment.BookmarkDialogCreationListener
import com.duckduckgo.app.browser.autoComplete.BrowserAutoCompleteSuggestionsAdapter
import com.duckduckgo.app.browser.omnibar.OnBackKeyListener
import com.duckduckgo.app.global.ViewModelFactory
import com.duckduckgo.app.global.view.*
import com.duckduckgo.app.privacymonitor.model.PrivacyGrade
import com.duckduckgo.app.privacymonitor.renderer.icon
import dagger.android.support.AndroidSupportInjection
import kotlinx.android.synthetic.main.fragment_browser_tab.*
import kotlinx.android.synthetic.main.popup_window_browser_menu.view.*
import org.jetbrains.anko.doAsync
import org.jetbrains.anko.uiThread
import timber.log.Timber
import javax.inject.Inject


class BrowserTabFragment : Fragment(), BookmarkDialogCreationListener {

    @Inject
    lateinit var webViewClient: BrowserWebViewClient

    @Inject
    lateinit var webChromeClient: BrowserChromeClient

    val tabId get() = arguments!![TAB_ID_ARG] as String

    @Inject
    lateinit var viewModelFactory: ViewModelFactory

    private val viewModel: BrowserViewModel by lazy {
        val model = ViewModelProviders.of(this, viewModelFactory).get(BrowserViewModel::class.java)
        model.setTabId(tabId)
        model
    }

    private lateinit var autoCompleteSuggestionsAdapter: BrowserAutoCompleteSuggestionsAdapter

    private var acceptingRenderUpdates = true

    private val privacyGradeMenu: MenuItem?
        get() = toolbar.menu.findItem(R.id.privacy_dashboard_menu_item)

    private val fireMenu: MenuItem?
        get() = toolbar.menu.findItem(R.id.fire_menu_item)

    private lateinit var popupMenu: BrowserPopupMenu

    private var pendingFileDownload: PendingFileDownload? = null

    private lateinit var webView: WebView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_browser_tab, container, false)
    }

    override fun onAttach(context: Context?) {
        AndroidSupportInjection.inject(this)
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        createPopupMenu()
        configureToolbar()
        configureWebView()
        configureOmnibarTextInput()
        configureDummyViewTouchHandler()
        configureAutoComplete()
        configureObservers()
    }

    private fun createPopupMenu() {
        popupMenu = BrowserPopupMenu(layoutInflater)
        val view = popupMenu.contentView
        popupMenu.apply {
            enableMenuOption(view.forwardPopupMenuItem) { webView.goForward() }
            enableMenuOption(view.backPopupMenuItem) { webView.goBack() }
            enableMenuOption(view.refreshPopupMenuItem) { webView.reload() }
            enableMenuOption(view.tabsPopupMenuItem) { browserActivity.launchTabs() }
            enableMenuOption(view.newTabPopupMenuItem) { browserActivity.launchNewTab() }
            enableMenuOption(view.bookmarksPopupMenuItem) { browserActivity.launchBookmarks() }
            enableMenuOption(view.addBookmarksPopupMenuItem) { addBookmark() }
            enableMenuOption(view.settingsPopupMenuItem) { browserActivity.launchSettings() }
        }
    }

    private fun configureToolbar() {

        toolbar.inflateMenu(R.menu.menu_browser_activity)

        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.privacy_dashboard_menu_item -> {
                    browserActivity.launchPrivacyDashboard()
                    return@setOnMenuItemClickListener true
                }
                R.id.fire_menu_item -> {
                    browserActivity.launchFire()
                    return@setOnMenuItemClickListener true
                }
                R.id.browser_popup_menu_item -> {
                    launchPopupMenu()
                    return@setOnMenuItemClickListener true
                }
                else -> return@setOnMenuItemClickListener false
            }
        }

        viewModel.privacyGrade.observe(this, Observer<PrivacyGrade> {
            it?.let {
                privacyGradeMenu?.icon = context?.getDrawable(it.icon())
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val view = layoutInflater.inflate(R.layout.include_duckduckgo_browser_webview, webViewContainer, true)
        webView = view.findViewById(R.id.browserWebView) as WebView
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(true)
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            pendingFileDownload = PendingFileDownload(url, Environment.DIRECTORY_DOWNLOADS)

            downloadFileWithPermissionCheck()
        }

        webView.setOnTouchListener { _, _ ->
            if (omnibarTextInput.isFocused) {
                focusDummy.requestFocus()
            }
            false
        }

        registerForContextMenu(webView)

        viewModel.registerWebViewListener(webViewClient, webChromeClient)
    }

    private fun configureObservers() {
        viewModel.viewState.observe(this, Observer<BrowserViewModel.ViewState> {
            it?.let { render(it) }
        })

        viewModel.url.observe(this, Observer {
            it?.let { webView.loadUrl(it) }
        })

        viewModel.command.observe(this, Observer {
            processCommand(it)
        })
    }

    private fun processCommand(it: BrowserViewModel.Command?) {
        when (it) {
            BrowserViewModel.Command.Refresh -> webView.reload()
            is BrowserViewModel.Command.Navigate -> {
                focusDummy.requestFocus()
                webView.loadUrl(it.url)
            }
//            BrowserViewModel.Command.LandingPage -> finishActivityAnimated()
            is BrowserViewModel.Command.DialNumber -> {
                val intent = Intent(Intent.ACTION_DIAL)
                intent.data = Uri.parse("tel:${it.telephoneNumber}")
//                launchExternalActivity(intent)
            }
            is BrowserViewModel.Command.SendEmail -> {
                val intent = Intent(Intent.ACTION_SENDTO)
                intent.data = Uri.parse(it.emailAddress)
//                launchExternalActivity(intent)
            }
            is BrowserViewModel.Command.SendSms -> {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${it.telephoneNumber}"))
                startActivity(intent)
            }
            BrowserViewModel.Command.ShowKeyboard -> {
                omnibarTextInput.postDelayed({ omnibarTextInput.showKeyboard() }, 300)
            }
            BrowserViewModel.Command.HideKeyboard -> {
                omnibarTextInput.hideKeyboard()
                focusDummy.requestFocus()
            }
            BrowserViewModel.Command.ReinitialiseWebView -> {
                webView.clearHistory()
            }
            is BrowserViewModel.Command.ShowFullScreen -> {
                webViewFullScreenContainer.addView(
                    it.view, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            is BrowserViewModel.Command.DownloadImage -> {
                pendingFileDownload = PendingFileDownload(it.url, Environment.DIRECTORY_PICTURES)
                downloadFileWithPermissionCheck()
            }
        }
    }

    private fun configureAutoComplete() {
        autoCompleteSuggestionsList.layoutManager = LinearLayoutManager(context)
        autoCompleteSuggestionsAdapter = BrowserAutoCompleteSuggestionsAdapter(
            immediateSearchClickListener = {
                userEnteredQuery(it.phrase)
            },
            editableSearchClickListener = {
                viewModel.onUserSelectedToEditQuery(it.phrase)
            }
        )
        autoCompleteSuggestionsList.adapter = autoCompleteSuggestionsAdapter
    }

    private fun render(viewState: BrowserViewModel.ViewState) {

        Timber.v("Rendering view state: $viewState")

        if (!acceptingRenderUpdates) return

        when (viewState.browserShowing) {
            true -> webView.show()
            false -> webView.hide()
        }

        when (viewState.isLoading) {
            true -> pageLoadingIndicator.show()
            false -> pageLoadingIndicator.hide()
        }

        if (shouldUpdateOmnibarTextInput(viewState, viewState.omnibarText)) {
            omnibarTextInput.setText(viewState.omnibarText)
            omnibarTextInput.post { omnibarTextInput.setSelection(omnibarTextInput.text.length) }
            appBarLayout.setExpanded(true, true)
        }

        pageLoadingIndicator.progress = viewState.progress

        when (viewState.showClearButton) {
            true -> showClearButton()
            false -> hideClearButton()
        }

        privacyGradeMenu?.isVisible = viewState.showPrivacyGrade
        fireMenu?.isVisible = viewState.showFireButton
        popupMenu.contentView.backPopupMenuItem.isEnabled = viewState.browserShowing && webView.canGoBack()
        popupMenu.contentView.forwardPopupMenuItem.isEnabled = viewState.browserShowing && webView.canGoForward()
        popupMenu.contentView.refreshPopupMenuItem.isEnabled = viewState.browserShowing
        popupMenu.contentView.addBookmarksPopupMenuItem?.isEnabled = viewState.canAddBookmarks

        when (viewState.showAutoCompleteSuggestions) {
            false -> autoCompleteSuggestionsList.gone()
            true -> {
                autoCompleteSuggestionsList.show()
                val results = viewState.autoCompleteSearchResults.suggestions
                autoCompleteSuggestionsAdapter.updateData(results)
            }
        }

        val immersiveMode = activity?.isImmersiveModeEnabled() ?: return
        when (viewState.isFullScreen) {
            true -> if (!immersiveMode) goFullScreen()
            false -> if (immersiveMode) exitFullScreen()
        }
    }

    private fun showClearButton() {
        omnibarTextInput.post {
            clearOmnibarInputButton.show()
            omnibarTextInput.updatePadding(paddingEnd = 40.toPx())
        }
    }

    private fun hideClearButton() {
        omnibarTextInput.post {
            clearOmnibarInputButton.hide()
            omnibarTextInput.updatePadding(paddingEnd = 10.toPx())
        }
    }

    private fun shouldUpdateOmnibarTextInput(viewState: BrowserViewModel.ViewState, omnibarInput: String?) =
        viewState.omnibarText != null && !viewState.isEditing && omnibarTextInput.isDifferent(omnibarInput)

    private fun configureOmnibarTextInput() {
        omnibarTextInput.onFocusChangeListener =
                View.OnFocusChangeListener { _, hasFocus: Boolean ->
                    viewModel.onOmnibarInputStateChanged(omnibarTextInput.text.toString(), hasFocus)
                }

        omnibarTextInput.addTextChangedListener(object : TextChangedWatcher() {

            override fun afterTextChanged(editable: Editable) {
                viewModel.onOmnibarInputStateChanged(
                    omnibarTextInput.text.toString(),
                    omnibarTextInput.hasFocus()
                )
            }
        })

        omnibarTextInput.onBackKeyListener = object : OnBackKeyListener {
            override fun onBackKey(): Boolean {
                focusDummy.requestFocus()
                return viewModel.userDismissedKeyboard()
            }
        }

        omnibarTextInput.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, keyEvent ->
            if (actionId == EditorInfo.IME_ACTION_DONE || keyEvent?.keyCode == KeyEvent.KEYCODE_ENTER) {
                userEnteredQuery(omnibarTextInput.text.toString())
                return@OnEditorActionListener true
            }
            false
        })

        clearOmnibarInputButton.setOnClickListener { omnibarTextInput.setText("") }
    }

    private fun userEnteredQuery(query: String) {
        viewModel.onUserSubmittedQuery(query)
    }

    private fun launchPopupMenu() {
        popupMenu.show(rootView, toolbar)
    }

    private fun goFullScreen() {
        Timber.i("Entering full screen")
        webViewFullScreenContainer.show()
        activity?.toggleFullScreen()
    }

    private fun exitFullScreen() {
        Timber.i("Exiting full screen")
        webViewFullScreenContainer.removeAllViews()
        webViewFullScreenContainer.gone()
        activity?.toggleFullScreen()
    }

    private fun downloadFileWithPermissionCheck() {
        if (hasWriteStoragePermission()) {
            downloadFile()
        } else {
            requestStoragePermission()
        }
    }

    private fun downloadFile() {
        val pending = pendingFileDownload
        pending?.let {
            val uri = Uri.parse(pending.url)
            val guessedFileName = URLUtil.guessFileName(pending.url, null, null)
            Timber.i("Guessed filename of $guessedFileName for url ${pending.url}")
            val request = DownloadManager.Request(uri).apply {
                allowScanningByMediaScanner()
                setDestinationInExternalPublicDir(pending.directory, guessedFileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            }
            val manager = activity?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager ?: return
            manager.enqueue(request)
            pendingFileDownload = null
            makeText(context, getString(R.string.webviewDownload), LENGTH_LONG).show()
        }
    }

    private fun hasWriteStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity!!, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestStoragePermission() {
        ActivityCompat.requestPermissions(
            activity!!, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            PERMISSION_REQUEST_EXTERNAL_STORAGE
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != PERMISSION_REQUEST_EXTERNAL_STORAGE) {
            if ((grantResults.isNotEmpty()) && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Timber.i("Permission granted")
                downloadFile()
            } else {
                Timber.i("Permission refused")
                Snackbar.make(toolbar, R.string.permissionRequiredToDownload, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateContextMenu(menu: ContextMenu, view: View, menuInfo: ContextMenu.ContextMenuInfo?) {
        webView.hitTestResult?.let {
            viewModel.userLongPressedInWebView(it, menu)
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        webView.hitTestResult?.let {
            val url = it.extra
            if (viewModel.userSelectedItemFromLongPressMenu(url, item)) {
                return true
            }
        }
        return super.onContextItemSelected(item)
    }

    /**
     * Dummy view captures touches on areas outside of the toolbar, before the WebView is visible
     */
    private fun configureDummyViewTouchHandler() {
        focusDummy.setOnTouchListener { _, _ ->
            // finishActivityAnimated()
            true
        }
    }

    private fun addBookmark() {
        val addBookmarkDialog = BookmarkAddEditDialogFragment.createDialogCreationMode(
            existingTitle = webView.title,
            existingUrl = webView.url
        )
        addBookmarkDialog.show(childFragmentManager, ADD_BOOKMARK_FRAGMENT_TAG)
    }

    fun clearViewPriorToAnimation() {
        acceptingRenderUpdates = false
        privacyGradeMenu?.isVisible = false
        omnibarTextInput.text.clear()
        omnibarTextInput.hideKeyboard()
        webView.hide()
    }

    override fun onSaveInstanceState(bundle: Bundle) {
        webView.saveState(bundle)
        super.onSaveInstanceState(bundle)
    }

    override fun onViewStateRestored(bundle: Bundle?) {
        super.onViewStateRestored(bundle)
        webView.restoreState(bundle)
    }

    override fun onDestroy() {
        webViewContainer?.removeAllViews()
        webView.destroy()
        popupMenu.dismiss()
        super.onDestroy()
    }

    private val browserActivity
        get() = activity as BrowserActivity

    override fun userWantsToCreateBookmark(title: String, url: String) {
        doAsync {
            viewModel.addBookmark(title, url)
            uiThread {
                Toast.makeText(context, R.string.bookmarkAddedFeedback, LENGTH_LONG).show()
            }
        }
    }

    private data class PendingFileDownload(
        val url: String,
        val directory: String
    )

    companion object {

        private const val TAB_ID_ARG = "TAB_ID_ARG"
        private const val ADD_BOOKMARK_FRAGMENT_TAG = "ADD_BOOKMARK"
        private const val PERMISSION_REQUEST_EXTERNAL_STORAGE = 200

        fun newInstance(tabId: String): BrowserTabFragment {
            val fragment = BrowserTabFragment()
            val args = Bundle()
            args.putString(TAB_ID_ARG, tabId)
            fragment.setArguments(args)
            return fragment
        }
    }

    fun goBack(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

}
