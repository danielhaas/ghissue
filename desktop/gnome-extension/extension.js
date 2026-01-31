import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import GObject from 'gi://GObject';
import St from 'gi://St';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';

const BUS_NAME = 'com.github.ghissue';
const OBJECT_PATH = '/com/github/ghissue';
const IFACE_NAME = 'com.github.ghissue';

const _extensionDir = Gio.File.new_for_uri(import.meta.url).get_parent().get_path();
const ICON_PATH = _extensionDir + '/ghissue-icon.svg';

const GhissueButton = GObject.registerClass(
class GhissueButton extends PanelMenu.Button {
    _init() {
        super._init(0.0, 'ghissue');

        this._icon = new St.Icon({
            gicon: Gio.icon_new_for_string(ICON_PATH),
            style_class: 'system-status-icon',
        });
        this.add_child(this._icon);

        this._daemonRunning = false;
        this._updateStyle();

        // Build right-click menu
        this._settingsItem = new PopupMenu.PopupMenuItem('Settings...');
        this._settingsItem.connect('activate', () => this._dbusCall('OpenSettings'));
        this.menu.addMenuItem(this._settingsItem);

        this._queueItem = new PopupMenu.PopupMenuItem('Queue: 0 pending');
        this._queueItem.setSensitive(false);
        this.menu.addMenuItem(this._queueItem);

        this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());

        const quitItem = new PopupMenu.PopupMenuItem('Quit');
        quitItem.connect('activate', () => this._dbusCall('Quit'));
        this.menu.addMenuItem(quitItem);

        // Refresh queue count when menu opens
        this.menu.connect('open-state-changed', (menu, open) => {
            if (open)
                this._refreshQueueCount();
        });
    }

    // Override vfunc_event to fully control click behavior.
    // PanelMenu.Button's default toggles the menu on any click;
    // we want left-click → CreateIssue, right-click → menu.
    vfunc_event(event) {
        if (event.type() === Clutter.EventType.BUTTON_RELEASE) {
            const button = event.get_button();
            if (button === Clutter.BUTTON_PRIMARY) {
                this._dbusCall('CreateIssue');
                return Clutter.EVENT_STOP;
            }
            if (button === Clutter.BUTTON_SECONDARY) {
                this.menu.toggle();
                return Clutter.EVENT_STOP;
            }
        } else if (event.type() === Clutter.EventType.TOUCH_END) {
            this._dbusCall('CreateIssue');
            return Clutter.EVENT_STOP;
        }
        return Clutter.EVENT_PROPAGATE;
    }

    setDaemonRunning(running) {
        this._daemonRunning = running;
        this._updateStyle();
    }

    _updateStyle() {
        this._icon.set_opacity(this._daemonRunning ? 255 : 128);
    }

    _dbusCall(method) {
        if (!this._daemonRunning)
            return;

        Gio.DBus.session.call(
            BUS_NAME, OBJECT_PATH, IFACE_NAME, method,
            null, null, Gio.DBusCallFlags.NONE, 5000, null,
            (conn, res) => {
                try { conn.call_finish(res); }
                catch (e) { logError(e, `ghissue: ${method} failed`); }
            }
        );
    }

    _refreshQueueCount() {
        if (!this._daemonRunning)
            return;

        Gio.DBus.session.call(
            BUS_NAME, OBJECT_PATH, IFACE_NAME, 'GetQueueCount',
            null,
            new GLib.VariantType('(i)'),
            Gio.DBusCallFlags.NONE, 5000, null,
            (conn, res) => {
                try {
                    const reply = conn.call_finish(res);
                    const [count] = reply.deepUnpack();
                    if (count > 0) {
                        this._queueItem.label.set_text(`Queue: ${count} pending`);
                        this._queueItem.visible = true;
                    } else {
                        this._queueItem.visible = false;
                    }
                } catch (e) {
                    logError(e, 'ghissue: GetQueueCount failed');
                }
            }
        );
    }
});

export default class GhissueExtension {
    enable() {
        this._button = new GhissueButton();
        Main.panel.addToStatusArea('ghissue', this._button);

        this._watchId = Gio.bus_watch_name(
            Gio.BusType.SESSION,
            BUS_NAME,
            Gio.BusNameWatcherFlags.NONE,
            () => this._button?.setDaemonRunning(true),
            () => this._button?.setDaemonRunning(false),
        );
    }

    disable() {
        if (this._watchId) {
            Gio.bus_unwatch_name(this._watchId);
            this._watchId = null;
        }
        this._button?.destroy();
        this._button = null;
    }
}
