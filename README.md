Task is to build implement a P2P file transfer network using a Circular Distributed Hash Table.

To run the program, open a number of terminals and use the following:

``java cdht [peer] [peer_succ1] [peer_succ_2] [MSS] [dropout_prob]``

- *peer* is an id for this terminal
- *peer_succ1* is an id for a peer in another terminal as the successor to peer 1 in the CHDT.
- *peer_succ2* is an id for a peer in another terminal as the second succesor to peer1 in the CDHT.
- *MSS* is the maximum segment size (set to 400).
- *dropout_prob* is the probability of a packet loss in transferring the file.

You should make sure the peer numbers create a valid CDHT.

e.g 1 2 3, 2 3 4, 3 4 1, 4 1 2 is valid.

In the terminal you can type the following commands:

- **quit** will gracefully remove this peer from the CDHT.
- **request [filenum]** will send the file from the CDHT to the current terminal.
