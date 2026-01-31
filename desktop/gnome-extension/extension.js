import Gio from 'gi://Gio';
import GObject from 'gi://GObject';
import St from 'gi://St';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';

const BUS_NAME = 'com.github.ghissue';
const OBJECT_PATH = '/com/github/ghissue';
const IFACE_NAME = 'com.github.ghissue';

// Resolve the extension directory from import.meta.url so the icon is
// found regardless of where the extension is installed.
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

        this.connectObject('button-press-event', () => {
            this._callCreateIssue();
            return true;  // handled — suppress default menu toggle
        }, this);
    }

    setDaemonRunning(running) {
        this._daemonRunning = running;
        this._updateStyle();
    }

    _updateStyle() {
        if (this._daemonRunning) {
            this._icon.remove_style_class_name('ghissue-inactive');
            this._icon.set_opacity(255);
        } else {
            this._icon.add_style_class_name('ghissue-inactive');
            this._icon.set_opacity(128);
        }
    }

    _callCreateIssue() {
        if (!this._daemonRunning)
            return;

        Gio.DBus.session.call(
            BUS_NAME,
            OBJECT_PATH,
            IFACE_NAME,
            'CreateIssue',
            null,
            null,
            Gio.DBusCallFlags.NONE,
            5000,
            null,
            (conn, res) => {
                try {
                    conn.call_finish(res);
                } catch (e) {
                    logError(e, 'ghissue: CreateIssue call failed');
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
