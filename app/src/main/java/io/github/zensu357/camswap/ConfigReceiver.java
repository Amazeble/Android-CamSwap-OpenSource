package io.github.zensu357.camswap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import io.github.zensu357.camswap.utils.LogUtil;

public class ConfigReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (IpcContract.ACTION_REQUEST_CONFIG.equals(intent.getAction())) {
            String requesterPackage = intent.getStringExtra(IpcContract.EXTRA_REQUESTER_PACKAGE);
            if (requesterPackage == null || requesterPackage.isEmpty()) {
                LogUtil.log("【CS-Host】Config request missing requester_package, ignored");
                return;
            }

            LogUtil.log("【CS-Host】Received config request, sending current config");
            // Instantiate ConfigManager and set context to reload config
            ConfigManager cm = new ConfigManager();
            cm.setContext(context);

            try {
                context.getPackageManager().getPackageInfo(requesterPackage, 0);
            } catch (Exception e) {
                LogUtil.log("【CS-Host】Config request source package does not exist: " + requesterPackage);
                return;
            }

            java.util.Set<String> targetPackages = cm.getTargetPackages();
            if (!requesterPackage.equals(context.getPackageName())
                    && !targetPackages.isEmpty()
                    && !targetPackages.contains(requesterPackage)) {
                LogUtil.log("【CS-Host】Refusing to send config to unauthorized package: " + requesterPackage);
                return;
            }

            // Send broadcast response
            cm.sendConfigBroadcast(context, requesterPackage);
        }
    }
}
