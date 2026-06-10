{
  description = "dev env";
  inputs = {
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1"; # tracks nixpkgs unstable branch
    devshell.url = "github:numtide/devshell";
    devshell.inputs.nixpkgs.follows = "nixpkgs";
    devenv.url = "https://flakehub.com/f/ramblurr/nix-devenv/*";
    devenv.inputs.nixpkgs.follows = "nixpkgs";
    clojure-nix-locker.url = "github:bevuta/clojure-nix-locker";
    clojure-nix-locker.inputs.nixpkgs.follows = "nixpkgs";
  };
  outputs =
    inputs@{
      clojure-nix-locker,
      self,
      devenv,
      devshell,
      ...
    }:
    let
      jdk = "jdk25";
    in
    devenv.lib.mkFlake ./. {
      inherit inputs;
      systems = [ "x86_64-linux" ];
      withOverlays = [
        devshell.overlays.default
        devenv.overlays.default
      ];
      packages = rec {
        vinyl =
          pkgs:
          let
            jdkPackage = pkgs.${jdk};
            lockerPkgs = pkgs // {
              clojure = pkgs.clojure.override { jdk = jdkPackage; };
            };
            clojure = pkgs.clojure.override { jdk = jdkPackage; };
            gitRev =
              if self ? rev then
                self.rev
              else if self ? dirtyRev then
                self.dirtyRev
              else
                "dirty";
            vlcLibraryPath = pkgs.lib.makeLibraryPath [ pkgs.vlc ];
            clojureLocker = (import "${clojure-nix-locker}/default.nix" { pkgs = lockerPkgs; }).lockfile {
              src = ./.;
              lockfile = "./deps-lock.json";
              extraPrepInputs = [ pkgs.git ];
            };
          in
          pkgs.stdenv.mkDerivation {
            pname = "vinyl";
            version = "0.1";
            src = ./.;
            nativeBuildInputs = [
              clojure
              pkgs.coreutils
              pkgs.findutils
              pkgs.git
              pkgs.vlc
              jdkPackage
            ];
            GIT_REV = gitRev;
            JAVA_HOME = jdkPackage.home;
            LD_LIBRARY_PATH = vlcLibraryPath;
            buildPhase = ''
              runHook preBuild

              source ${clojureLocker.shellEnv}
              export JAVA_HOME="${jdkPackage.home}"
              export JAVA_CMD="${jdkPackage}/bin/java"
              export LD_LIBRARY_PATH="${vlcLibraryPath}"

              clojure -Srepro -T:build jar

              runHook postBuild
            '';
            doCheck = true;
            checkPhase = ''
              runHook preCheck

              source ${clojureLocker.shellEnv}
              export JAVA_HOME="${jdkPackage.home}"
              export JAVA_CMD="${jdkPackage}/bin/java"
              export LD_LIBRARY_PATH="${vlcLibraryPath}"

              clojure -Srepro -M:dev:kaocha

              runHook postCheck
            '';
            installPhase = ''
              runHook preInstall

              mkdir -p $out
              cp "$(find target -type f -name '*.jar' -print | head -n 1)" $out/

              runHook postInstall
            '';
          };
        default = vinyl;
        locker =
          pkgs:
          let
            jdkPackage = pkgs.${jdk};
            lockerPkgs = pkgs // {
              clojure = pkgs.clojure.override { jdk = jdkPackage; };
            };
            clojure = pkgs.clojure.override { jdk = jdkPackage; };
            clojureLocker = (import "${clojure-nix-locker}/default.nix" { pkgs = lockerPkgs; }).lockfile {
              src = ./.;
              lockfile = "./deps-lock.json";
              extraPrepInputs = [ pkgs.git ];
            };
          in
          clojureLocker.commandLocker ''
            export HOME="$tmp/home"
            export GITLIBS="$tmp/home/.gitlibs"
            unset CLJ_CACHE CLJ_CONFIG XDG_CACHE_HOME XDG_CONFIG_HOME XDG_DATA_HOME
            export GIT_REV="lockfile-generation"

            ${clojure}/bin/clojure -Srepro -X:deps prep :aliases "[:dev :kaocha]"
            ${clojure}/bin/clojure -Srepro -P -M:dev:kaocha
            ${clojure}/bin/clojure -Srepro -T:build jar
          '';
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
            (if self ? packages then self.packages.${pkgs.system}.locker else pkgs.deps-lock)
          ];
        };
    };
}
