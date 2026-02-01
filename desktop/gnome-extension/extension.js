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

const GhissueRepoButton = GObject.registerClass(
class GhissueRepoButton extends PanelMenu.Button {
    _init(owner, repoName, color, extension) {
        super._init(0.0, `ghissue-${owner}-${repoName}`);

        this._owner = owner;
        this._repoName = repoName;
        this._color = color;
        this._extension = extension;
        this._daemonRunning = false;

        // Layout: colored circle + repo name
        const hbox = new St.BoxLayout({ style_class: 'panel-status-menu-box' });

        this._dot = new St.Widget({
            style: `background-color: ${color}; border-radius: 7px; width: 14px; height: 14px; margin: 0 4px 0 0;`,
            y_align: Clutter.ActorAlign.CENTER,
        });
        hbox.add_child(this._dot);

        this._label = new St.Label({
            text: repoName,
            y_align: Clutter.ActorAlign.CENTER,
            style: 'font-size: 0.9em;',
        });
        hbox.add_child(this._label);

        this.add_child(hbox);
        this._updateStyle();

        // Shared right-click menu
        const settingsItem = new PopupMenu.PopupMenuItem('Settings...');
        settingsItem.connect('activate', () => this._dbusCall('OpenSettings'));
        this.menu.addMenuItem(settingsItem);

        this._queueItem = new PopupMenu.PopupMenuItem('Queue: 0 pending');
        this._queueItem.setSensitive(false);
        this.menu.addMenuItem(this._queueItem);

        this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());

        const quitItem = new PopupMenu.PopupMenuItem('Quit');
        quitItem.connect('activate', () => this._dbusCall('Quit'));
        this.menu.addMenuItem(quitItem);

        this.menu.connect('open-state-changed', (menu, open) => {
            if (open)
                this._refreshQueueCount();
        });
    }

    vfunc_event(event) {
        if (event.type() === Clutter.EventType.BUTTON_RELEASE) {
            const button = event.get_button();
            if (button === Clutter.BUTTON_PRIMARY) {
                this._dbusCallCreateIssue();
                return Clutter.EVENT_STOP;
            }
            if (button === Clutter.BUTTON_SECONDARY) {
                this.menu.toggle();
                return Clutter.EVENT_STOP;
            }
        } else if (event.type() === Clutter.EventType.TOUCH_END) {
            this._dbusCallCreateIssue();
            return Clutter.EVENT_STOP;
        }
        return Clutter.EVENT_PROPAGATE;
    }

    setDaemonRunning(running) {
        this._daemonRunning = running;
        this._updateStyle();
    }

    _updateStyle() {
        const opacity = this._daemonRunning ? 255 : 128;
        this._dot.set_opacity(opacity);
        this._label.set_opacity(opacity);
    }

    _dbusCallCreateIssue() {
        if (!this._daemonRunning)
            return;

        Gio.DBus.session.call(
            BUS_NAME, OBJECT_PATH, IFACE_NAME, 'CreateIssue',
            new GLib.Variant('(ss)', [this._owner, this._repoName]),
            null, Gio.DBusCallFlags.NONE, 5000, null,
            (conn, res) => {
                try { conn.call_finish(res); }
                catch (e) { logError(e, 'ghissue: CreateIssue failed'); }
            }
        );
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
        this._buttons = [];
        this._daemonRunning = false;

        this._watchId = Gio.bus_watch_name(
            Gio.BusType.SESSION,
            BUS_NAME,
            Gio.BusNameWatcherFlags.NONE,
            () => {
                this._daemonRunning = true;
                this._rebuildButtons();
            },
            () => {
                this._daemonRunning = false;
                for (const btn of this._buttons)
                    btn.setDaemonRunning(false);
            },
        );

        // Listen for ReposChanged signal
        this._signalId = Gio.DBus.session.signal_subscribe(
            BUS_NAME, IFACE_NAME, 'ReposChanged',
            OBJECT_PATH, null, Gio.DBusSignalFlags.NONE,
            () => this._rebuildButtons(),
        );
    }

    disable() {
        if (this._signalId) {
            Gio.DBus.session.signal_unsubscribe(this._signalId);
            this._signalId = null;
        }
        if (this._watchId) {
            Gio.bus_unwatch_name(this._watchId);
            this._watchId = null;
        }
        this._destroyButtons();
    }

    _destroyButtons() {
        for (const btn of this._buttons)
            btn.destroy();
        this._buttons = [];
    }

    _rebuildButtons() {
        if (!this._daemonRunning)
            return;

        // Fetch repos from daemon
        Gio.DBus.session.call(
            BUS_NAME, OBJECT_PATH, IFACE_NAME, 'GetRepos',
            null,
            new GLib.VariantType('(s)'),
            Gio.DBusCallFlags.NONE, 5000, null,
            (conn, res) => {
                try {
                    const reply = conn.call_finish(res);
                    const [jsonStr] = reply.deepUnpack();
                    const repos = JSON.parse(jsonStr);
                    this._applyRepos(repos);
                } catch (e) {
                    logError(e, 'ghissue: GetRepos failed');
                }
            }
        );
    }

    _applyRepos(repos) {
        this._destroyButtons();

        for (const repo of repos) {
            const btn = new GhissueRepoButton(
                repo.owner, repo.name, repo.color, this
            );
            btn.setDaemonRunning(this._daemonRunning);
            Main.panel.addToStatusArea(
                `ghissue-${repo.owner}-${repo.name}`, btn
            );
            this._buttons.push(btn);
        }
    }
}
