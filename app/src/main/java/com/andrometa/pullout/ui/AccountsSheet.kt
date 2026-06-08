package com.andrometa.pullout.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrometa.pullout.R
import com.andrometa.pullout.auth.AuthServices
import com.andrometa.pullout.auth.CookieStore
import com.andrometa.pullout.auth.LoginActivity
import com.andrometa.pullout.auth.ServiceConfig
import com.andrometa.pullout.databinding.ItemAccountBinding
import com.andrometa.pullout.databinding.SheetAccountsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class AccountsSheet : BottomSheetDialogFragment() {
    private var _b: SheetAccountsBinding? = null
    private val b get() = _b!!

    /** Fired after any cookie change (login/logout) so the host can restart cobalt. */
    var onCookiesChanged: (() -> Unit)? = null

    private lateinit var adapter: AccountAdapter

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                onCookiesChanged?.invoke()
            }
            refresh()
        }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        SheetAccountsBinding.inflate(i, c, false).also { _b = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AccountAdapter(
            onLogin = { cfg ->
                loginLauncher.launch(
                    Intent(requireContext(), LoginActivity::class.java)
                        .putExtra(LoginActivity.EXTRA_SERVICE, cfg.id)
                )
            },
            onLogout = { cfg ->
                CookieStore.removeService(requireContext(), cfg.id)
                clearCookiesForDomains(cfg)
                onCookiesChanged?.invoke()
                refresh()
            }
        )
        b.recyclerAccounts.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerAccounts.adapter = adapter
        refresh()
    }

    private fun refresh() {
        val loggedIn = CookieStore.loggedInServices(requireContext())
        adapter.submit(AuthServices.ALL, loggedIn)
    }

    private fun clearCookiesForDomains(cfg: ServiceConfig) {
        val cm = CookieManager.getInstance()
        for (domain in cfg.cookieDomains) {
            val existing = cm.getCookie(domain) ?: continue
            for (pair in existing.split(";")) {
                val key = pair.trim().substringBefore("=").trim()
                if (key.isNotEmpty()) {
                    // Expire each cookie on its domain.
                    cm.setCookie(domain, "$key=; Max-Age=0")
                }
            }
        }
        cm.flush()
    }

    override fun onDestroyView() { super.onDestroyView(); _b = null }

    companion object {
        const val TAG = "AccountsSheet"
        fun newInstance() = AccountsSheet()
    }

    // ── Inline adapter ──────────────────────────────────────────────────────
    private class AccountAdapter(
        val onLogin: (ServiceConfig) -> Unit,
        val onLogout: (ServiceConfig) -> Unit
    ) : RecyclerView.Adapter<AccountAdapter.VH>() {

        private var items: List<ServiceConfig> = emptyList()
        private var loggedIn: Set<String> = emptySet()

        fun submit(list: List<ServiceConfig>, logged: Set<String>) {
            items = list
            loggedIn = logged
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAccountBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val cfg = items[position]
            val isIn = loggedIn.contains(cfg.id)
            val ctx = holder.itemView.context
            holder.b.tvServiceName.text = cfg.displayName
            holder.b.tvServiceStatus.text =
                ctx.getString(if (isIn) R.string.account_status_in else R.string.account_status_out)
            holder.b.statusDot.setBackgroundColor(
                ctx.getColor(if (isIn) R.color.neon_green else R.color.text_secondary)
            )
            holder.b.btnAccountAction.text =
                ctx.getString(if (isIn) R.string.account_logout else R.string.account_login)
            holder.b.btnAccountAction.setTextColor(
                ctx.getColor(if (isIn) R.color.neon_red else R.color.neon_cyan)
            )
            holder.b.btnAccountAction.setOnClickListener {
                if (isIn) onLogout(cfg) else onLogin(cfg)
            }
        }

        class VH(val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root)
    }
}
