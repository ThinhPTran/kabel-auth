(defproject io.replikativ/kabel-auth "0.1.0-SNAPSHOT"
  :description "Authentication middleware for kabel."
  :url "https://github.com/replikativ/kabel-auth"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]

                 [io.replikativ/kabel "0.1.4"] ;; only needed for logging! TODO refactor
                 [io.replikativ/hasch "0.3.0"]
                 [io.replikativ/konserve "0.3.5"]

                 [io.replikativ/full.async "0.9.1-SNAPSHOT"]])
