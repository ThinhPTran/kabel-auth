# kabel-auth

This is an authentication middleware for
[kabel](https://github.com/replikativ/kabel). It is implemented according to [passwordless authentication](https://medium.com/@ninjudd/passwords-are-obsolete-9ed56d483eb):

> Here’s how passwordless authentication works in more detail:

>     Instead of asking users for a password when they try to log in
>     to your app or website, just ask them for their username (or
>     email or mobile phone number).  Create a temporary
>     authorization code on the backend receiver and store it in your
>     database.  Send the user an email or SMS with a link that
>     contains the code.  The user clicks the link which opens your
>     app or website and sends the authorization code to your receiver.
>     On your backend receiver, verify that the code is valid and
>     exchange it for a long-lived token, which is stored in your
>     database and sent back to be stored on the sender device as
>     well.  The user is now logged in, and doesn’t have to repeat
>     this process again until their token expires or they want to
>     authenticate on a new device.

It is used in [replikativ](https://github.com/replikativ/replikativ)
to build a p2p network. The middleware is symmetric, so both sides
need to authenticate each other. There are two levels of
authentication. One is a trust based one where you can whitelist
connections (e.g. classical clients receiving messages from a trusted
server) to other peers from kabel. The other is the passwordless
authentication over a secondary channel. We provide a secondary
channel for e-mail + url atm., feel free to extend it to new
providers.


Note that this also allows to implement password authentication by
using the same kabel channels to request the password, so the
secondary channel is then the primary one.

## Usage

You can instantiate the middleware like this:

~~~clojure
(auth (atom #{"wss://trusted:80/some-app/ws"})
      receiver-token-store ;; some (dedicated) konserve store
      sender-token-store ;; some (dedicated) konserve store
      "wss://localhost:8080/some-app/ws" ;; remote of this connection (same as for kabel connection)
      ;; decide which messages need protection
      (fn [{:keys [type]}] (or ({:state-changing-msg-type :auth} type)
                               :unrelated))
      ;; notification when authentication is needed
      (fn [protocol user] (alert! "Check channel " protocol " for " user))
      [peer [in out]])
~~~

Furthermore you have to provide a secondary channel for authentication:

TODO


## Todo
   - add public/private key authentication of signed messages as a third level

## License

Copyright © 2016 Christian Weilbach

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
