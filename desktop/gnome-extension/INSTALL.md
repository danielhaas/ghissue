# ghissue GNOME Shell Extension — Installation

## Requirements

- GNOME Shell 48
- The ghissue daemon running on D-Bus (`com.github.ghissue`)

## Install from release

1. Download `ghissue@njoerd.com.zip` from the
   [latest release](https://github.com/danielhaas/ghissue/releases/latest).

2. Install with:

   ```sh
   gnome-extensions install ghissue@njoerd.com.zip
   ```

3. Restart GNOME Shell (log out and back in on Wayland, or press
   `Alt+F2` → `r` → Enter on X11).

4. Enable the extension:

   ```sh
   gnome-extensions enable ghissue@njoerd.com
   ```

## Install from source

```sh
mkdir -p ~/.local/share/gnome-shell/extensions/ghissue@njoerd.com
cp desktop/gnome-extension/extension.js ~/.local/share/gnome-shell/extensions/ghissue@njoerd.com/
cp desktop/gnome-extension/metadata.json ~/.local/share/gnome-shell/extensions/ghissue@njoerd.com/
cp desktop/resources/ghissue-icon.svg ~/.local/share/gnome-shell/extensions/ghissue@njoerd.com/
```

Then restart GNOME Shell and enable:

```sh
gnome-extensions enable ghissue@njoerd.com
```

## Uninstall

```sh
gnome-extensions uninstall ghissue@njoerd.com
```
