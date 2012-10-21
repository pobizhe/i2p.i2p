package net.i2p.router.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.i2p.router.RouterContext;
import net.i2p.router.web.PluginStarter;
import net.i2p.update.*;

/**
 * Check for or download an updated version of a plugin.
 * A plugin is a standard .sud file with a 40-byte signature,
 * a 16-byte version, and a .zip file.
 *
 * So we get the current version and update URL for the installed plugin,
 * then fetch the first 56 bytes of the URL, extract the version,
 * and compare.
 *
 * Moved from web/ and turned into an Updater.
 *
 * @since 0.7.12
 * @author zzz
 */
class PluginUpdateHandler implements Checker, Updater {
    private final RouterContext _context;
    private final ConsoleUpdateManager _mgr;

    public PluginUpdateHandler(RouterContext ctx, ConsoleUpdateManager mgr) {
        _context = ctx;
        _mgr = mgr;
    }
    
    /** check a single plugin */
    @Override
    public UpdateTask check(UpdateType type, UpdateMethod method,
                            String appName, String currentVersion, long maxTime) {
        if ((type != UpdateType.PLUGIN) ||
            method != UpdateMethod.HTTP || appName.length() <= 0)
            return null;

        Properties props = PluginStarter.pluginProperties(_context, appName);
        String oldVersion = props.getProperty("version");
        String xpi2pURL = props.getProperty("updateURL");
        List<URI> updateSources = null;
        if (xpi2pURL != null) {
            try {
                updateSources = Collections.singletonList(new URI(xpi2pURL));
            } catch (URISyntaxException use) {}
        }

        if (oldVersion == null || updateSources == null) {
            //updateStatus("<b>" + _("Cannot check, plugin {0} is not installed", appName) + "</b>");
            return null;
        }

        UpdateRunner update = new PluginUpdateChecker(_context, _mgr, updateSources, appName, oldVersion);
        update.start();
        return update;
    }
    
    /** download a single plugin */
    @Override
    public UpdateTask update(UpdateType type, UpdateMethod method, List<URI> updateSources,
                               String appName, String newVersion, long maxTime) {
        if ((type != UpdateType.PLUGIN && type != UpdateType.PLUGIN_INSTALL) ||
            method != UpdateMethod.HTTP || updateSources.isEmpty())
            return null;
        Properties props = PluginStarter.pluginProperties(_context, appName);
        String oldVersion = props.getProperty("version");
        String xpi2pURL = props.getProperty("updateURL");
        if (oldVersion == null || xpi2pURL == null) {
            //updateStatus("<b>" + _("Cannot check, plugin {0} is not installed", appName) + "</b>");
            return null;
        }

        UpdateRunner update = new PluginUpdateRunner(_context, _mgr, updateSources, appName, oldVersion);
        // set status before thread to ensure UI feedback
        _mgr.notifyProgress(update, "<b>" + _mgr._("Updating") + "</b>");
        update.start();
        return update;
    }
}
    
