package com.labserver.lablink.ui

import android.content.Context
import com.labserver.lablink.R
import com.labserver.lablink.net.ManagerResult

/** What to tell the user when a bot-manager call failed. */
fun managerErrorText(ctx: Context, f: ManagerResult.Fail): String = when (f.kind) {
    ManagerResult.Kind.NotConnected -> ctx.getString(R.string.mgr_error_not_connected)
    ManagerResult.Kind.NoKey -> ctx.getString(R.string.mgr_error_no_key)
    ManagerResult.Kind.BadUrl -> ctx.getString(R.string.mgr_error_url)
    ManagerResult.Kind.Auth -> ctx.getString(R.string.mgr_error_auth)
    ManagerResult.Kind.LimitReached -> ctx.getString(R.string.mgr_error_limit)
    ManagerResult.Kind.TrashFull -> ctx.getString(R.string.mgr_error_trash_full)
    ManagerResult.Kind.NotFound -> ctx.getString(R.string.mgr_error_not_found)
    ManagerResult.Kind.Unreachable -> ctx.getString(R.string.mgr_error_unreachable) + if (f.detail.isNotBlank()) " (${f.detail})" else ""
    ManagerResult.Kind.Invalid, ManagerResult.Kind.Other -> f.detail.ifBlank { ctx.getString(R.string.mgr_error_unreachable) }
}
