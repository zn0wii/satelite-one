package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.model.DnsOverrideEntry
import com.interstellar.proxy.data.model.isValidIpLiteral
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.IosCard
import com.interstellar.proxy.ui.components.IosHairline
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSectionLabel
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.iosPressable
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

@Composable
fun DnsOverridesPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val entries by viewModel.dnsOverrides.collectAsState()
    var editing by remember { mutableStateOf<DnsOverrideEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.dns_add),
                color = colors.accent,
                fontSize = 17.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .iosPressable { creating = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IosSectionLabel(stringResource(R.string.dns_title))
            if (entries.isEmpty()) {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.dns_empty_hint),
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            DnsOverrideRow(
                                entry = entry,
                                onToggle = { viewModel.setDnsOverrideEnabled(entry.id, it) },
                                onClick = { editing = entry },
                            )
                            if (index != entries.lastIndex) IosHairline(startInset = 16.dp)
                        }
                    }
                }
            }
            IosSectionFooter(
                stringResource(R.string.dns_footer),
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (creating || editing != null) {
        DnsOverrideEditorSheet(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { entry ->
                viewModel.upsertDnsOverride(entry)
                creating = false
                editing = null
            },
            onDelete = editing?.let { existing ->
                {
                    viewModel.removeDnsOverride(existing.id)
                    creating = false
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun DnsOverrideRow(
    entry: DnsOverrideEntry,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(entry.displayName().ifBlank { stringResource(R.string.dns_unnamed) }, color = colors.text, fontSize = 17.sp)
            Text(
                stringResource(R.string.dns_entry_summary, entry.parsedDomains().size, entry.ip),
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
        }
        IosSwitch(checked = entry.enabled, onChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsOverrideEditorSheet(
    initial: DnsOverrideEntry?,
    onDismiss: () -> Unit,
    onSave: (DnsOverrideEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalInterstellarColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var domains by remember { mutableStateOf(initial?.domains ?: "") }
    var ip by remember { mutableStateOf(initial?.ip ?: "") }
    val entryId = remember { initial?.id ?: DnsOverridesStore.newId() }

    val draft = DnsOverrideEntry(
        id = entryId,
        enabled = initial?.enabled ?: true,
        domains = domains,
        ip = ip.trim(),
    )
    val domainsOk = draft.parsedDomains().isNotEmpty()
    val ipOk = isValidIpLiteral(ip)
    val canSave = domainsOk && ipOk

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.panelSolid,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (initial == null) stringResource(R.string.dns_editor_add_title) else stringResource(R.string.dns_editor_edit_title),
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.dns_field_domains), color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Field(
                value = domains,
                onChange = { domains = it },
                placeholder = stringResource(R.string.dns_field_domains_hint),
            )
            Spacer(Modifier.height(14.dp))

            Text(stringResource(R.string.dns_field_ip), color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Field(
                value = ip,
                onChange = { ip = it },
                placeholder = stringResource(R.string.dns_field_ip_hint),
            )

            Spacer(Modifier.height(8.dp))
            val hint = when {
                !domainsOk -> stringResource(R.string.dns_hint_no_domains)
                !ipOk -> stringResource(R.string.dns_hint_bad_ip)
                else -> stringResource(R.string.dns_hint_summary, draft.parsedDomains().size, draft.ip)
            }
            Text(hint, color = if (domainsOk && !ipOk) colors.warning else colors.textTertiary, fontSize = 12.sp)

            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canSave) colors.primary else colors.bgDeep)
                    .then(if (canSave) Modifier.pressableClick { onSave(draft) } else Modifier)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.dns_save), color = if (canSave) colors.onPrimary else colors.textTertiary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .pressableClick { onDelete() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.dns_delete), color = colors.danger, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    val colors = LocalInterstellarColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = colors.textTertiary, fontSize = 13.sp) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedBorderColor = colors.primaryBorder,
            unfocusedBorderColor = colors.border,
            cursorColor = colors.primary,
        ),
    )
}
