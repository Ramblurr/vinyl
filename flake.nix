{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
    clj-helpers.url = "github:outskirtslabs/clojure-nix-locker-helpers";
    clj-helpers.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs =
    inputs@{
      self,
      devenv,
      devshell,
      clj-helpers,
      ...
    }:
    let
      package =
        pkgs:
        clj-helpers.lib.mkCljLib {
          inherit pkgs;
          name = "vinyl";
          version = "0.1";
          src = ./.;
          prepAliases = [
            "dev"
            "kaocha"
          ];
          prefetchAliases = [ "dev:kaocha" ];
          checkCommand = "clojure -Srepro -M:dev:kaocha";
          gitRev = clj-helpers.lib.gitRev self;
          nativeBuildInputs = [ pkgs.vlc ];
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.vlc ];
        };
    in
    devenv.lib.mkFlake ./. {
      inherit inputs;
      systems = [ "x86_64-linux" ];
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = {
        vinyl = package;
        default = package;
        # regenerates ./deps-lock.json: `nix run .#locker`
        locker = pkgs: (package pkgs).locker;
      };
      checks = {
        vinyl = pkgs: self.packages.${pkgs.system}.vinyl;
      };
      devShell =
        pkgs:
        pkgs.devshell.mkShell {
          imports = [
            devenv.capsules.base
            devenv.capsules.clojure
          ];

          commands = [
            { package = pkgs.vlc; }
          ];

          env = [
            {
              name = "LD_LIBRARY_PATH";
              value = pkgs.lib.makeLibraryPath [ pkgs.vlc ];
            }
          ];

          packages = [
            (
              if self ? packages then
                self.packages.${pkgs.system}.locker
              else
                clj-helpers.packages.${pkgs.system}.deps-lock
            )
          ];
        };
    };
}
