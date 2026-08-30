package top.syshub.accountsx.common.accounts.model;

import java.io.IOException;

public class PlayerNoLongerExistedException extends IOException {
    public PlayerNoLongerExistedException(String message) {
        super(message);
    }
}
